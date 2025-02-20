package io.joern.x2cpg.passes.base

import io.shiftleft.codepropertygraph.generated.{Cpg, PropertyNames}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, NodeTypes}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** This pass has MethodStubCreator and TypeDeclStubCreator as prerequisite for language frontends which do not provide
  * method stubs and type decl stubs.
  */
class ContainsEdgePass(cpg: Cpg) extends ForkJoinParallelCpgPass[AstNode](cpg) {
  import ContainsEdgePass._

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  override def generateParts(): Array[AstNode] =
    cpg.graph.nodes(sourceTypes*).cast[AstNode].toArray

  override def runOnPart(dstGraph: DiffGraphBuilder, source: AstNode): Unit = {
    try {
      // AST is assumed to be a tree. If it contains cycles, then this will give a nice endless loop with OOM
      val queue = mutable.ArrayDeque[StoredNode](source)
      while (queue.nonEmpty) {
        val parent = queue.removeHead()
        for (nextNode <- parent._astOut) {
          if (isDestinationType(nextNode)) dstGraph.addEdge(source, nextNode, EdgeTypes.CONTAINS)
          if (!isSourceType(nextNode)) queue.append(nextNode)
        }
      }
    } catch {
      case ex: Exception =>
        logger.warn(
          s"Error in ContainsEdgePass for node in file '${source.propertyOption(PropertyNames.FILENAME).toString}''",
          ex
        )
    }
  }
}

object ContainsEdgePass {

  private def isSourceType(node: StoredNode): Boolean = node match {
    case _: Method | _: TypeDecl | _: File => true
    case _                                 => false
  }

  private def isDestinationType(node: StoredNode): Boolean = node match {
    case _: Block | _: Identifier | _: FieldIdentifier | _: Return | _: Method | _: TypeDecl | _: Call | _: Literal |
        _: MethodRef | _: TypeRef | _: ControlStructure | _: JumpTarget | _: Unknown | _: TemplateDom =>
      true
    case _ => false
  }

  private val sourceTypes = List(NodeTypes.METHOD, NodeTypes.TYPE_DECL, NodeTypes.FILE)

}
