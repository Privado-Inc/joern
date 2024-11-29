package io.joern.x2cpg.passes.controlflow.cfgdominator

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.codepropertygraph.generated.nodes.{Method, StoredNode}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory
import scala.collection.mutable

/** This pass has no prerequisites.
  */
class CfgDominatorPass(cpg: Cpg) extends ForkJoinParallelCpgPass[Method](cpg) {
  private val logger                          = LoggerFactory.getLogger(getClass)
  override def generateParts(): Array[Method] = cpg.method.toArray

  override def runOnPart(dstGraph: DiffGraphBuilder, method: Method): Unit = {
    try {
      val cfgAdapter          = new CpgCfgAdapter()
      val dominatorCalculator = new CfgDominator(cfgAdapter)

      val reverseCfgAdapter       = new ReverseCpgCfgAdapter()
      val postDominatorCalculator = new CfgDominator(reverseCfgAdapter)

      val cfgNodeToImmediateDominator = dominatorCalculator.calculate(method)
      addDomTreeEdges(dstGraph, cfgNodeToImmediateDominator)

      val cfgNodeToPostImmediateDominator = postDominatorCalculator.calculate(method.methodReturn)
      addPostDomTreeEdges(dstGraph, cfgNodeToPostImmediateDominator)
    } catch {
      case ex: Exception =>
        logger.warn(s"Error for the METHOD node -> '${method.fullName}' in file '${method.filename}'")
    }

  }

  private def addDomTreeEdges(
    dstGraph: DiffGraphBuilder,
    cfgNodeToImmediateDominator: mutable.LinkedHashMap[StoredNode, StoredNode]
  ): Unit = {
    cfgNodeToImmediateDominator.foreach { case (node, immediateDominator) =>
      dstGraph.addEdge(immediateDominator, node, EdgeTypes.DOMINATE)
    }
  }

  private def addPostDomTreeEdges(
    dstGraph: DiffGraphBuilder,
    cfgNodeToPostImmediateDominator: mutable.LinkedHashMap[StoredNode, StoredNode]
  ): Unit = {
    cfgNodeToPostImmediateDominator.foreach { case (node, immediatePostDominator) =>
      dstGraph.addEdge(immediatePostDominator, node, EdgeTypes.POST_DOMINATE)
    }
  }
}
