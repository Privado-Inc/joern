package io.joern.x2cpg.passes.base

import io.joern.x2cpg.utils.LinkingUtil
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.codepropertygraph.generated.nodes.StoredNode
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

class AstLinkerPass(cpg: Cpg) extends CpgPass(cpg) with LinkingUtil {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  override def run(dstGraph: DiffGraphBuilder): Unit = {
    try {
      cpg.method.whereNot(_.astParent).foreach { method =>
        addAstParent(method, method.fullName, method.astParentType, method.astParentFullName, dstGraph)
      }
      cpg.typeDecl.whereNot(_.astParent).foreach { typeDecl =>
        addAstParent(typeDecl, typeDecl.fullName, typeDecl.astParentType, typeDecl.astParentFullName, dstGraph)
      }
      cpg.member.whereNot(_.astParent).foreach { member =>
        addAstParent(
          member,
          s"${member.astParentFullName}.${member.name}",
          member.astParentType,
          member.astParentFullName,
          dstGraph
        )
      }
    } catch {
      case ex: Exception =>
        logger.warn(s"Error in AstLinkerPass", ex)
    }
  }

  /** For the given method or type declaration, determine its parent in the AST via the AST_PARENT_TYPE and
    * AST_PARENT_FULL_NAME fields and create an AST edge from the parent to it. AST creation to methods and type
    * declarations is deferred in frontends in order to allow them to process methods/type- declarations independently.
    */
  private def addAstParent(
    astChild: StoredNode,
    astChildFullName: String,
    astParentType: String,
    astParentFullName: String,
    dstGraph: DiffGraphBuilder
  ): Unit = {
    try {
      val astParentOption: Option[StoredNode] =
        astParentType match {
          case NodeTypes.METHOD          => methodFullNameToNode(cpg, astParentFullName)
          case NodeTypes.TYPE_DECL       => typeDeclFullNameToNode(cpg, astParentFullName)
          case NodeTypes.NAMESPACE_BLOCK => namespaceBlockFullNameToNode(cpg, astParentFullName)
          case _ =>
            logger.warn(
              s"Invalid AST_PARENT_TYPE=$astParentFullName;" +
                s" astChild LABEL=${astChild.label};" +
                s" astChild FULL_NAME=$astChildFullName"
            )
            None
        }

      astParentOption match {
        case Some(astParent) =>
          dstGraph.addEdge(astParent, astChild, EdgeTypes.AST)
        case None =>
          logFailedSrcLookup(EdgeTypes.AST, astParentType, astParentFullName, astChild.label, astChild.id.toString)
      }
    } catch {
      case ex: Exception =>
        logger.warn(
          s"Error in AstLinkerPass for node in file '${astChild.propertyOption(PropertyNames.FILENAME).toString}''",
          ex
        )
    }
  }
}
