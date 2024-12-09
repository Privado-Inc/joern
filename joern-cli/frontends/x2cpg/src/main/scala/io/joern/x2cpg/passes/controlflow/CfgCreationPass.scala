package io.joern.x2cpg.passes.controlflow

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*
import io.joern.x2cpg.passes.controlflow.cfgcreation.CfgCreator
import org.slf4j.LoggerFactory

/** A pass that creates control flow graphs from abstract syntax trees.
  *
  * Control flow graphs can be calculated independently per method. Therefore, we inherit from
  * `ConcurrentWriterCpgPass`.
  *
  * Note: the version of OverflowDB that we currently use as a storage backend does not assign ids to edges and this
  * pass only creates edges at the moment. Therefore, we currently do without key pools.
  */
class CfgCreationPass(cpg: Cpg) extends ForkJoinParallelCpgPass[Method](cpg) {
  private val logger                          = LoggerFactory.getLogger(getClass)
  override def generateParts(): Array[Method] = cpg.method.toArray

  override def runOnPart(diffGraph: DiffGraphBuilder, method: Method): Unit = {
    val localDiff = Cpg.newDiffGraphBuilder
    try {
      new CfgCreator(method, localDiff).run()
      diffGraph.absorb(localDiff)
    } catch {
      case ex: Exception =>
        logger.error(s"Error for the METHOD node -> '${method.fullName}' in file '${method.filename}'")
    }
  }

}
