package io.joern.gosrc2cpg.passes

import better.files.File
import io.joern.gosrc2cpg.Config
import io.joern.gosrc2cpg.astcreation.AstCreator
import io.joern.gosrc2cpg.datastructures.GoGlobal
import io.joern.gosrc2cpg.model.GoModHelper
import io.shiftleft.codepropertygraph.generated.Cpg
import org.slf4j.{Logger, LoggerFactory}

class InitialMainSrcPass(
  cpg: Cpg,
  astFiles: List[String],
  config: Config,
  goMod: GoModHelper,
  goGlobal: GoGlobal,
  tmpDir: File
) extends BasePassForAstProcessing(cpg, astFiles, config, goMod, goGlobal, tmpDir) {
  protected override val logger: Logger = LoggerFactory.getLogger(classOf[InitialMainSrcPass])

  def processAst(diffGraph: DiffGraphBuilder, astCreator: AstCreator): Unit = {
    astCreator.identifyUsedPackages()
  }
}
