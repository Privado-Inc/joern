package io.joern.x2cpg.passes.callgraph

import io.joern.x2cpg.utils.LinkingUtil
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.passes.CpgPass
import org.slf4j.LoggerFactory
import overflowdb.traversal.*

/** This pass has MethodStubCreator and TypeDeclStubCreator as prerequisite for language frontends which do not provide
  * method stubs and type decl stubs.
  */
class MethodRefLinker(cpg: Cpg) extends CpgPass(cpg) with LinkingUtil {
  private val logger = LoggerFactory.getLogger(getClass)

  private val srcLabels = List(NodeTypes.METHOD_REF)

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    try {
      // Create REF edges from METHOD_REFs to METHOD
      linkToSingle(
        cpg,
        srcNodes = cpg.graph.nodes(srcLabels*).toList,
        srcLabels = List(NodeTypes.METHOD_REF),
        dstNodeLabel = NodeTypes.METHOD,
        edgeType = EdgeTypes.REF,
        dstNodeMap = methodFullNameToNode(cpg, _),
        dstFullNameKey = PropertyNames.METHOD_FULL_NAME,
        dstGraph,
        None
      )
    } catch {
      case ex: Exception =>
        logger.warn(s"Error in MethodRefLinker", ex)
    }
  }

}
