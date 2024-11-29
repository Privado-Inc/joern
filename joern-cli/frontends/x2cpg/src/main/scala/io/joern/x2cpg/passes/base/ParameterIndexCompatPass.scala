package io.joern.x2cpg.passes.base

import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.PropertyNames
import io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn.PropertyDefaults
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

/** Old CPGs use the `order` field to indicate the parameter index while newer CPGs use the `parameterIndex` field. This
  * pass checks whether `parameterIndex` is not set, in which case the value of `order` is copied over.
  */
class ParameterIndexCompatPass(cpg: Cpg) extends CpgPass(cpg) {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    try {
      cpg.parameter.foreach { param =>
        if (param.index == PropertyDefaults.Index) {
          diffGraph.setNodeProperty(param, PropertyNames.INDEX, param.order)
        }
      }
    } catch {
      case ex: Exception =>
        logger.warn(s"Error in ParameterIndexCompatPass", ex)
    }
  }

}
