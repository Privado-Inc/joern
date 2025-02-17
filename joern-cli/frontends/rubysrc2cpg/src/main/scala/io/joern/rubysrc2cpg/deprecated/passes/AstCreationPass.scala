package io.joern.rubysrc2cpg.deprecated.passes

import io.joern.rubysrc2cpg.Config
import io.joern.rubysrc2cpg.deprecated.astcreation.{AstCreator, ResourceManagedParser}
import io.joern.rubysrc2cpg.deprecated.parser.DeprecatedRubyParser
import io.joern.rubysrc2cpg.deprecated.utils.{PackageContext, PackageTable}
import io.joern.x2cpg.SourceFiles
import io.joern.x2cpg.datastructures.Global
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.EnumerationHasAsScala

class AstCreationPass(
  cpg: Cpg,
  fileNameAndContext: (String, DeprecatedRubyParser.ProgramContext),
  packageTable: PackageTable,
  config: Config
) extends CpgPass(cpg) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def run(diffGraph: DiffGraphBuilder): Unit = {
    val (fileName, context) = fileNameAndContext
    try {
      logger.error(s"Processing AST for file - $fileName")
      diffGraph.absorb(
        new AstCreator(fileName, context, PackageContext(fileName, packageTable), cpg.metaData.root.headOption)(
          config.schemaValidation
        ).createAst()
      )
    } catch {
      case ex: Exception =>
        logger.error(s"Error while processing AST for file - $fileName - ", ex)
    }
  }
}
