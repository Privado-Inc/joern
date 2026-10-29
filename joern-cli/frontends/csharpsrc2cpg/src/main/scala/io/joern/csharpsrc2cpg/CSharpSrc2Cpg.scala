package io.joern.csharpsrc2cpg

import better.files.File
import io.joern.csharpsrc2cpg.astcreation.AstCreator
import io.joern.csharpsrc2cpg.datastructures.CSharpProgramSummary
import io.joern.csharpsrc2cpg.parser.DotNetJsonParser
import io.joern.csharpsrc2cpg.passes.{AstCreationPass, DependencyPass}
import io.joern.csharpsrc2cpg.utils.{DependencyDownloader, DotNetAstGenRunner}
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.astgen.AstGenRunner.AstGenRunnerResult
import io.joern.x2cpg.astgen.ParserResult
import io.joern.x2cpg.passes.callgraph.NaiveCallLinker
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.joern.x2cpg.utils.{ConcurrentTaskUtil, Environment, HashUtil, Report}
import io.joern.x2cpg.{SourceFiles, X2CpgFrontend}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.Languages
import io.shiftleft.passes.CpgPassBase
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class CSharpSrc2Cpg extends X2CpgFrontend[Config] {

  private val logger         = LoggerFactory.getLogger(getClass)
  private val report: Report = new Report()
  // Keep pending diff graphs bounded without limiting parsing and AST creation to a small number of workers.
  private val astBatchSize = 256

  override def createCpg(config: Config): Try[Cpg] = {
    withNewEmptyCpg(config.outputPath, config) { (cpg, config) =>
      File.usingTemporaryDirectory("csharpsrc2cpgOut") { tmpDir =>
        val astGenResult = new DotNetAstGenRunner(config).execute(tmpDir)
        // Parse and summarize inside bounded worker tasks. Each worker retains only its current parsed JSON tree.
        val summaryResults = ConcurrentTaskUtil
          .runUsingThreadPool(astGenResult.parsedFiles.iterator.map { file => () =>
            val astCreator = CSharpSrc2Cpg.astCreatorForFile(file, config)
            Paths.get(astCreator.parserResult.fullPath) -> Try(astCreator.summarize())
          })
          .map(_.get)
        val internalProgramSummary =
          summaryResults.foldLeft(CSharpProgramSummary(imports = CSharpProgramSummary.initialImports)) {
            case (summary, (_, fileSummary)) =>
              fileSummary match {
                case Failure(exception) =>
                  logger.warn("Unable to pre-parse C# file, skipping - ", exception)
                  summary
                case Success(fileSummary) => summary ++= fileSummary
              }
          }
        // Load built-in and/or external summaries for missing imports
        val builtinSummary            = createBuiltinSummary(config, internalProgramSummary)
        val internalAndBuiltinSummary = internalProgramSummary ++= builtinSummary
        val externalSummary           = createExternalSummary(config, internalAndBuiltinSummary)
        val localSummary              = internalAndBuiltinSummary ++= externalSummary

        val hash = HashUtil.sha256(summaryResults.map(_._1))
        new MetaDataPass(cpg, Languages.CSHARPSRC, config.inputPath, Option(hash)).createAndApply()

        val packageIds = mutable.HashSet.empty[String]
        new DependencyPass(cpg, buildFiles(config), packageIds.add).createAndApply()
        // If "download dependencies" is enabled, then fetch dependencies and resolve their symbols for additional types
        val programSummary = if (config.downloadDependencies) {
          DependencyDownloader(cpg, config, localSummary, packageIds.toSet).download()
        } else {
          localSummary
        }
        astGenResult.parsedFiles.grouped(astBatchSize).foreach { files =>
          new AstCreationPass(
            cpg,
            files,
            file => CSharpSrc2Cpg.astCreatorForFile(file, config).withSummary(programSummary),
            report
          ).createAndApply()
        }
        TypeNodePass.withTypesFromCpg(cpg).createAndApply()
        report.print()
      }
    }
  }

  private def createBuiltinSummary(config: Config, internalSummary: CSharpProgramSummary): CSharpProgramSummary = {
    if (config.useBuiltinSummaries) {
      CSharpProgramSummary(
        mutable.Map
          .fromSpecific(CSharpProgramSummary.BuiltinTypes.view.filterKeys(internalSummary.imports))
          .result()
      )
    } else {
      CSharpProgramSummary()
    }
  }

  private def createExternalSummary(config: Config, internalSummary: CSharpProgramSummary): CSharpProgramSummary = {
    if (config.externalSummaryPaths.nonEmpty) {
      CSharpProgramSummary(
        mutable.Map
          .fromSpecific(
            CSharpProgramSummary
              .fromExternalJsons(config.externalSummaryPaths)
              .view
              .filterKeys(internalSummary.imports)
          )
          .result()
      )
    } else {
      CSharpProgramSummary()
    }
  }

  private def buildFiles(config: Config): List[String] = {
    SourceFiles.determine(
      config.inputPath,
      Set(".csproj"),
      Option(config.defaultIgnoredFilesRegex),
      Option(config.ignoredFilesRegex),
      Option(config.ignoredFiles)
    )
  }

}

object CSharpSrc2Cpg {

  private[csharpsrc2cpg] def astCreatorForFile(file: String, config: Config): AstCreator = {
    val parserResult = DotNetJsonParser.readFile(Paths.get(file))
    val relativeFileName = if (Environment.operatingSystem == Environment.OperatingSystemType.Windows) {
      handleWinUserTemp(parserResult.fullPath, config.inputPath)
    } else {
      SourceFiles.toRelativePath(parserResult.fullPath, config.inputPath)
    }
    new AstCreator(relativeFileName, parserResult)(config.schemaValidation)
  }

  def postProcessingPasses(cpg: Cpg, config: Config): List[CpgPassBase] = List(new NaiveCallLinker(cpg))

  /** Parses the generated AST Gen files in parallel and produces AstCreators from each.
    */
  def processAstGenRunnerResults(astFiles: List[String], config: Config): Seq[AstCreator] = {
    Await.result(
      Future.sequence(
        astFiles
          .map(file =>
            Future {
              astCreatorForFile(file, config)
            }
          )
      ),
      Duration.Inf
    )
  }

  /** Addresses behaviour in Windows where a user-specific temp folder is used: parserResult.fullPath =
    * C:\Users\runneradmin\AppData\Local\Temp\... config.inputPath = C:\Users\RUNNER~1\AppData\Local\Temp\...
    *
    * @param inputPath
    *   the user-specified input path.
    * @param parserResultFullPath
    *   the full path according to the parser result.
    * @return
    *   the relative file, robust to user-specific temporary folders are used, as is the case with GitHub runners.
    */
  private def handleWinUserTemp(parserResultFullPath: String, inputPath: String): String = {
    if (parserResultFullPath.contains("Temp") && inputPath.contains("Temp")) {
      SourceFiles.toRelativePath(
        parserResultFullPath.substring(parserResultFullPath.indexOf("Temp")),
        inputPath.substring(inputPath.indexOf("Temp"))
      )
    } else {
      SourceFiles.toRelativePath(parserResultFullPath, inputPath)
    }
  }

}
