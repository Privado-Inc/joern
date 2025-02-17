package io.joern.rubysrc2cpg

import better.files.File
import io.joern.rubysrc2cpg.astcreation.AstCreator
import io.joern.rubysrc2cpg.astcreation.RubyIntermediateAst.StatementList
import io.joern.rubysrc2cpg.datastructures.RubyProgramSummary
import io.joern.rubysrc2cpg.deprecated.parser.DeprecatedRubyParser
import io.joern.rubysrc2cpg.deprecated.parser.DeprecatedRubyParser.*
import io.joern.rubysrc2cpg.parser.{RubyAstGenRunner, RubyJsonParser, RubyJsonToNodeCreator}
import io.joern.rubysrc2cpg.passes.{
  AstCreationPass,
  ConfigFileCreationPass,
  DependencyPass,
  DependencySummarySolverPass
}
import io.joern.rubysrc2cpg.utils.DependencyDownloader
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.frontendspecific.rubysrc2cpg.*
import io.joern.x2cpg.passes.base.AstLinkerPass
import io.joern.x2cpg.passes.callgraph.NaiveCallLinker
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass, XTypeRecoveryConfig}
import io.joern.x2cpg.utils.ExternalCommand.ExternalCommandResult
import io.joern.x2cpg.utils.{ConcurrentTaskUtil, ExternalCommand}
import io.joern.x2cpg.{SourceFiles, X2CpgFrontend}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.shiftleft.passes.CpgPassBase
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory
import upickle.default.*

import java.nio.file.{Files, Paths}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try, Using}

class RubySrc2Cpg extends X2CpgFrontend[Config] {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def createCpg(config: Config): Try[Cpg] = {
    withNewEmptyCpg(config.outputPath, config: Config) { (cpg, config) =>
      new MetaDataPass(cpg, Languages.RUBYSRC, config.inputPath).createAndApply()
      new ConfigFileCreationPass(cpg).createAndApply()
      new DependencyPass(cpg).createAndApply()
      if (config.useDeprecatedFrontend) {
        deprecatedCreateCpgAction(cpg, config)
      } else {
        newCreateCpgAction(cpg, config)
      }
    }
  }

  private def newCreateCpgAction(cpg: Cpg, config: Config): Unit = {
    File.usingTemporaryDirectory("rubysrc2cpgOut") { tmpDir =>
      val astGenResult = RubyAstGenRunner(config).execute(tmpDir)

      val astCreators = ConcurrentTaskUtil
        .runUsingThreadPool(
          RubySrc2Cpg.processAstGenRunnerResults(astGenResult.parsedFiles, config, cpg.metaData.root.headOption)
        )
        .flatMap {
          case Failure(exception)  => logger.warn(s"Unable to parse Ruby file, skipping -", exception); None
          case Success(astCreator) => Option(astCreator)
        }
        .filter(x => {
          if x.fileContent.isBlank then logger.info(s"File content empty, skipping - ${x.fileName}")
          !x.fileContent.isBlank
        })

      val internalProgramSummary = ConcurrentTaskUtil
        .runUsingThreadPool(astCreators.map(x => () => x.summarize()).iterator)
        .flatMap {
          case Failure(exception) => logger.warn(s"Unable to pre-parse Ruby file, skipping - ", exception); None
          case Success(summary)   => Option(summary)
        }
        .foldLeft(RubyProgramSummary(RubyProgramSummary.BuiltinTypes(config.typeStubMetaData)))(_ ++= _)

      val dependencySummary = if (config.downloadDependencies) {
        DependencyDownloader(cpg).download()
      } else {
        RubyProgramSummary()
      }

      val programSummary = internalProgramSummary ++= dependencySummary

      AstCreationPass(cpg, astCreators.map(_.withSummary(programSummary))).createAndApply()
      if config.downloadDependencies then {
        DependencySummarySolverPass(cpg, dependencySummary).createAndApply()
      }
      TypeNodePass.withTypesFromCpg(cpg).createAndApply()
    }
  }

  private def deprecatedCreateCpgAction(cpg: Cpg, config: Config): Unit = try {
    Using.resource(new deprecated.astcreation.ResourceManagedParser(config.antlrCacheMemLimit)) { parser =>
      if (config.downloadDependencies && !scala.util.Properties.isWin) {
        val tempDir = File.newTemporaryDirectory()
        try {
          downloadDependency(config.inputPath, tempDir.toString())
          new deprecated.passes.AstPackagePass(
            cpg,
            tempDir.toString(),
            parser,
            RubySrc2Cpg.packageTableInfo,
            config.inputPath
          )(config.schemaValidation).createAndApply()
        } finally {
          tempDir.delete()
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
              parser.parse(x) match
                case Failure(exception) =>
                  logger.warn(s"Could not parse file: $x, skipping", exception); throw exception
                case Success(ast) => x -> ast
          )
          .iterator
        ConcurrentTaskUtil.runUsingThreadPool(tasks).flatMap(_.toOption)
      }

      new io.joern.rubysrc2cpg.deprecated.ParseInternalStructures(parsedFiles, cpg.metaData.root.headOption)
        .populatePackageTable()
      parsedFiles.foreach(parsedFile =>
        new deprecated.passes.AstCreationPass(cpg, parsedFile, RubySrc2Cpg.packageTableInfo, config).createAndApply()
      )
    }
  } finally {
    RubySrc2Cpg.packageTableInfo.clear()
  }

  private def downloadDependency(inputPath: String, tempPath: String): Unit = {
    if (Files.isRegularFile(Paths.get(s"${inputPath}${java.io.File.separator}Gemfile"))) {
      ExternalCommand.run(Seq("bundle", "config", "set", "--local", "path", tempPath), inputPath) match {
        case ExternalCommandResult(0, stdOut, _) =>
          logger.info(s"Gem config successfully done")
        case ExternalCommandResult(_, stdOut, _) =>
          logger.error(s"Error while configuring Gem Path: ${stdOut.mkString(System.lineSeparator())}")
      }
      val command = Seq("bundle", "install")
      ExternalCommand.run(command, inputPath) match {
        case ExternalCommandResult(0, stdOut, _) =>
          logger.info(s"Dependency installed successfully")
        case ExternalCommandResult(_, stdOut, _) =>
          logger.error(s"Error while downloading dependency: ${stdOut.mkString(System.lineSeparator())}")
      }
    }
  }
}

object RubySrc2Cpg {

  // TODO: Global mutable state is bad and should be avoided in the next iteration of the Ruby frontend
  val packageTableInfo                              = new deprecated.utils.PackageTable()
  private val RubySourceFileExtensions: Set[String] = Set(".rb")

  def postProcessingPasses(cpg: Cpg, config: Config): List[CpgPassBase] = {
    if (config.useDeprecatedFrontend) {
      List(new deprecated.passes.RubyImportResolverPass(cpg, packageTableInfo))
        ++ new deprecated.passes.RubyTypeRecoveryPassGenerator(cpg).generate() ++ List(
          new deprecated.passes.RubyTypeHintCallLinker(cpg),
          new NaiveCallLinker(cpg),

          // Some of the passes above create new methods, so, we
          // need to run the ASTLinkerPass one more time
          new AstLinkerPass(cpg)
        )
    } else {
      val implicitRequirePass = if (cpg.dependency.name.contains("zeitwerk")) ImplicitRequirePass(cpg) :: Nil else Nil
      implicitRequirePass ++ List(ImportsPass(cpg), RubyImportResolverPass(cpg)) ++
        new RubyTypeRecoveryPassGenerator(cpg, config = XTypeRecoveryConfig(iterations = 4))
          .generate() ++ List(new RubyTypeHintCallLinker(cpg), new NaiveCallLinker(cpg), new AstLinkerPass(cpg))
    }
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
      val rubyProgram    = new RubyJsonToNodeCreator().visitProgram(parserResult.json)
      val sourceFileName = parserResult.fullPath
      val fileContent    = File(sourceFileName).contentAsString
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
