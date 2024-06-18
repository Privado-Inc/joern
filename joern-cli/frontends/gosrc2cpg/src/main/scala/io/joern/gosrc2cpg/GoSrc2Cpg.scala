package io.joern.gosrc2cpg

import better.files.File
import io.joern.gosrc2cpg.datastructures.GoGlobal
import io.joern.gosrc2cpg.model.GoModHelper
import io.joern.gosrc2cpg.parser.GoAstJsonParser
import io.joern.gosrc2cpg.passes.{
  AstCreationPass,
  DownloadDependenciesPass,
  MethodAndTypeCacheBuilderPass,
  PackageCtorCreationPass
}
import io.joern.gosrc2cpg.utils.AstGenRunner
import io.joern.gosrc2cpg.utils.AstGenRunner.GoAstGenRunnerResult
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.joern.x2cpg.utils.Report
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Languages
import io.shiftleft.utils.StatsLogger

import java.nio.file.Paths
import scala.util.Try

class GoSrc2Cpg(goGlobalOption: Option[GoGlobal] = Option(GoGlobal())) extends X2CpgFrontend[Config] {
  private val report: Report = new Report()

  private var goMod: Option[GoModHelper] = None
  def createCpg(config: Config): Try[Cpg] = {
    withNewEmptyCpg(config.outputPath, config) { (cpg, config) =>
      File.usingTemporaryDirectory("gosrc2cpgOut") { tmpDir =>
        goGlobalOption
          .orElse(Option(GoGlobal()))
          .foreach(goGlobal => {
            new MetaDataPass(cpg, Languages.GOLANG, config.inputPath).createAndApply()
            StatsLogger.initiateNewStage("AST Generator")
            val astGenResult = new AstGenRunner(config).execute(tmpDir).asInstanceOf[GoAstGenRunnerResult]
            goMod = Some(
              new GoModHelper(
                Some(config),
                astGenResult.parsedModFile
                  .flatMap(modFile => GoAstJsonParser.readModFile(Paths.get(modFile)).map(x => x))
              )
            )
            StatsLogger.endLastStage()
            goGlobal.mainModule = goMod.flatMap(modHelper => modHelper.getModMetaData().map(mod => mod.module.name))
            StatsLogger.initiateNewStage("Type info cache builder")
            val astCreators =
              new MethodAndTypeCacheBuilderPass(Some(cpg), astGenResult.parsedFiles, config, goMod.get, goGlobal)
                .process()
            StatsLogger.endLastStage()
            if (config.fetchDependencies) {
              StatsLogger.initiateNewStage("Download dependencies pass")
              goGlobal.processingDependencies = true
              new DownloadDependenciesPass(goMod.get, goGlobal, config).process()
              goGlobal.processingDependencies = false
              StatsLogger.endLastStage()
            }
            new AstCreationPass(cpg, astCreators, report).createAndApply()
            if goGlobal.pkgLevelVarAndConstantAstMap.size() > 0 then
              new PackageCtorCreationPass(cpg, config, goGlobal).createAndApply()
            report.print()
          })
      }
    }
  }

  def getGoModHelper: GoModHelper = goMod.get
}
