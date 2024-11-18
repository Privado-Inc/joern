package io.joern.x2cpg.passes.typerelations

import io.joern.x2cpg.utils.LinkingUtil
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.TypeDecl
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, NodeTypes, PropertyNames}
import io.shiftleft.passes.CpgPass
import org.slf4j.{Logger, LoggerFactory}

class AliasLinkerPass(cpg: Cpg) extends CpgPass(cpg) with LinkingUtil {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  override def run(dstGraph: DiffGraphBuilder): Unit = {
    // Create ALIAS_OF edges from TYPE_DECL nodes to TYPE
    try {
      linkToMultiple(
        cpg,
        srcLabels = List(NodeTypes.TYPE_DECL),
        dstNodeLabel = NodeTypes.TYPE,
        edgeType = EdgeTypes.ALIAS_OF,
        dstNodeMap = typeFullNameToNode(cpg, _),
        getDstFullNames = (srcNode: TypeDecl) => {
          srcNode.aliasTypeFullName
        },
        dstFullNameKey = PropertyNames.ALIAS_TYPE_FULL_NAME,
        dstGraph
      )
    } catch {
      case ex: Exception =>
        logger.warn(s"Error in AliasLinkerPass", ex)
    }
  }

}
