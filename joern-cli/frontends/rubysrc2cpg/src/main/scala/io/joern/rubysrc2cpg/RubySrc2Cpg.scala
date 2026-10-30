package io.joern.rubysrc2cpg

import io.joern.rubysrc2cpg.astcreation.AstCreator
import io.joern.rubysrc2cpg.astcreation.RubyIntermediateAst.StatementList
import io.joern.rubysrc2cpg.datastructures.RubyProgramSummary
import io.joern.rubysrc2cpg.parser.*
import io.joern.rubysrc2cpg.parser.RubyAstGenRunner.JRubyEnvironment
import io.joern.rubysrc2cpg.passes.{AstCreationPass, ConfigFileCreationPass, DependencyPass}
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.passes.base.AstLinkerPass
import io.joern.x2cpg.passes.callgraph.NaiveCallLinker
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.joern.x2cpg.utils.ConcurrentTaskUtil
import io.joern.x2cpg.{SourceFiles, X2CpgFrontend}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.passes.CpgPassBase
import io.shiftleft.semanticcpg.utils.{ExternalCommand, ExternalCommandResult, FileUtil}
import org.slf4j.LoggerFactory
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try, Using}

class RubySrc2Cpg(sharedJRubyEnv: Option[JRubyEnvironment] = None) extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig: Config = Config()

  private val logger                                     = LoggerFactory.getLogger(this.getClass)
  private var rubyAstGenRunner: Option[RubyAstGenRunner] = None

  override def createCpg(config: Config): Try[Cpg] = {
    withNewEmptyCpg(config.outputPath, config: Config) { (cpg, config) =>
      new MetaDataPass(cpg, Languages.RUBYSRC, config.inputPath).createAndApply()
      new ConfigFileCreationPass(cpg, config).createAndApply()
      new DependencyPass(cpg).createAndApply()
      if (config.useDeprecatedFrontend) {
        deprecatedCreateCpgAction(cpg, config)
      } else {
        createCpgAction(cpg, config)
      }
    }
  }

  private def createCpgAction(cpg: Cpg, config: Config): Unit = {
    FileUtil.usingTemporaryDirectory("rubysrc2cpgOut") { tmpDir =>
      val astGenResult = rubyAstGenRunner match {
        case Some(astGenRunner) =>
          astGenRunner.execute(tmpDir, config)
        case None =>
          val astGenRunner = RubyAstGenRunner(config, sharedJRubyEnv)
          rubyAstGenRunner = Option(astGenRunner)
          astGenRunner.execute(tmpDir, config)
      }

      val astCreators = ConcurrentTaskUtil
        .runUsingThreadPool(
          RubySrc2Cpg.processAstGenRunnerResults(astGenResult.parsedFiles, config, cpg.metaData.root.headOption)
        )
        .flatMap {
          case Failure(exception)  => logger.warn(s"Unable to parse Ruby file, skipping -", exception); None
          case Success(astCreator) => Option(astCreator)
        }
        .filter { x =>
          if (x.fileContent.isBlank) { logger.info(s"File content empty, skipping - ${x.fileName}") }
          !x.fileContent.isBlank || x.fileName.endsWith(".html.erb")
        }

      val internalProgramSummary = ConcurrentTaskUtil
        .runUsingThreadPool(astCreators.map(x => () => x.summarize()).iterator)
        .flatMap {
          case Failure(exception) => logger.warn(s"Unable to pre-parse Ruby file, skipping - ", exception); None
          case Success(summary)   => Option(summary)
        }
        .foldLeft(RubyProgramSummary(RubyProgramSummary.BuiltinTypes(config)))(_ ++= _)

      AstCreationPass(cpg, astCreators.map(_.withSummary(internalProgramSummary))).createAndApply()
      TypeNodePass.withTypesFromCpg(cpg).createAndApply()
    }

  }

  private def deprecatedCreateCpgAction(cpg: Cpg, config: Config): Unit = try {
    Using.resource(new deprecated.astcreation.ResourceManagedParser(config.antlrCacheMemLimit)) { parser =>
      if (config.downloadDependencies && !scala.util.Properties.isWin) {
        val tempDir = Files.createTempDirectory("rubysrc2cpgDependencies")
        try {
          downloadDependency(config.inputPath, tempDir.toString)
          new deprecated.passes.AstPackagePass(
            cpg,
            tempDir.toString,
            parser,
            RubySrc2Cpg.packageTableInfo,
            config.inputPath
          )(config.schemaValidation).createAndApply()
        } finally {
          FileUtil.delete(tempDir, swallowIoExceptions = true)
        }
      }
      val parsedFiles = {
        val tasks = SourceFiles
          .determine(
            config.inputPath,
            RubySrc2Cpg.RubySourceFileExtensions,
            ignoredFilesRegex = Option(config.ignoredFilesRegex),
            ignoredFilesPath = Option(config.ignoredFiles)
          )
          .map(x =>
            () =>
              parser.parse(x) match {
                case Failure(exception) =>
                  logger.warn(s"Could not parse file: $x, skipping", exception); throw exception
                case Success(ast) => x -> ast
              }
          )
          .iterator
        ConcurrentTaskUtil.runUsingThreadPool(tasks).flatMap(_.toOption)
      }

      new deprecated.ParseInternalStructures(parsedFiles, cpg.metaData.root.headOption)
        .populatePackageTable()
      val astCreationPass =
        new deprecated.passes.AstCreationPass(cpg, parsedFiles, RubySrc2Cpg.packageTableInfo, config)
      astCreationPass.createAndApply()
    }
  } finally {
    RubySrc2Cpg.packageTableInfo.clear()
  }

  private def downloadDependency(inputPath: String, tempPath: String): Unit = {
    if (Files.isRegularFile(Paths.get(s"${inputPath}${java.io.File.separator}Gemfile"))) {
      ExternalCommand.run(
        Seq("bundle", "config", "set", "--local", "path", tempPath),
        Option(Paths.get(inputPath))
      ) match {
        case ExternalCommandResult(0, _, _, _, _) =>
          logger.info(s"Gem config successfully done")
        case ExternalCommandResult(_, stdOut, _, _, _) =>
          logger.error(s"Error while configuring Gem Path: ${stdOut.mkString(System.lineSeparator())}")
      }
      val command = Seq("bundle", "install")
      ExternalCommand.run(command, Option(Paths.get(inputPath))) match {
        case ExternalCommandResult(0, _, _, _, _) =>
          logger.info(s"Dependency installed successfully")
        case ExternalCommandResult(_, stdOut, _, _, _) =>
          logger.error(s"Error while downloading dependency: ${stdOut.mkString(System.lineSeparator())}")
      }
    }
  }

  override def close(): Unit = {
    val closeTask = Try(rubyAstGenRunner.foreach(_.close))
    if (closeTask.isFailure) {
      logger.error("Error occurred while cleaning up RubyAstGenRunner!", closeTask.failed.get)
    }
  }

}

object RubySrc2Cpg {

  // TODO: Global mutable state is bad and should be avoided in the next iteration of the Ruby frontend
  val packageTableInfo                              = new deprecated.utils.PackageTable()
  private val RubySourceFileExtensions: Set[String] = Set(".rb")

  def deprecatedPostProcessingPasses(cpg: Cpg): List[CpgPassBase] = {
    List(new deprecated.passes.RubyImportResolverPass(cpg, packageTableInfo))
      ++ new deprecated.passes.RubyTypeRecoveryPassGenerator(cpg).generate() ++ List(
        new deprecated.passes.RubyTypeHintCallLinker(cpg),
        new NaiveCallLinker(cpg),

        // Some of the passes above create new methods, so, we
        // need to run the ASTLinkerPass one more time
        new AstLinkerPass(cpg)
      )
  }

  /** Parses the generated AST Gen files in parallel and produces AstCreators from each.
    */
  def processAstGenRunnerResults(
    astFiles: List[String],
    config: Config,
    projectRoot: Option[String]
  ): Iterator[() => AstCreator] = {
    astFiles.map { fileName => () =>
      val parserResult   = RubyJsonParser.readFile(Paths.get(fileName))
      val rubyProgram    = new RubyJsonToNodeCreator(fileName = fileName).visitProgram(parserResult.json)
      val sourceFileName = parserResult.fullPath
      val fileContent    = new String(Files.readAllBytes(Paths.get(sourceFileName)), StandardCharsets.UTF_8)
      new AstCreator(
        sourceFileName,
        projectRoot,
        enableFileContents = !config.disableFileContent,
        fileContent = fileContent,
        rootNode = rubyProgram
      )(config.schemaValidation)
    }.iterator
  }

}
