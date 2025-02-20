package io.joern.x2cpg.passes.callgraph

import io.joern.x2cpg.utils.LinkingUtil
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

/** This pass has MethodStubCreator and TypeDeclStubCreator as prerequisite for language frontends which do not provide
  * method stubs and type decl stubs.
  */
class MethodRefLinker(cpg: Cpg) extends CpgPass(cpg) with LinkingUtil {
  private val logger = LoggerFactory.getLogger(getClass)

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    try {
      // Create REF edges from METHOD_REFs to METHOD
      linkToSingle(
        cpg,
        srcNodes = cpg.methodRef.l,
        srcLabels = List(NodeTypes.METHOD_REF),
        dstNodeLabel = NodeTypes.METHOD,
        edgeType = EdgeTypes.REF,
        dstNodeMap = methodFullNameToNode(cpg, _),
        dstFullNameKey = PropertyNames.METHOD_FULL_NAME,
        dstDefaultPropertyValue = Method.PropertyDefaults.FullName,
        dstGraph,
        None
      )
    } catch {
      case ex: Exception =>
        logger.warn(s"Error in MethodRefLinker", ex)
    }
  }

}
