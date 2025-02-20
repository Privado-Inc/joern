package io.joern.rubysrc2cpg.testfixtures

import java.io.File
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.language.Path
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, Semantics}
import io.joern.dataflowengineoss.testfixtures.{SemanticCpgTestFixture, SemanticTestCpg}
import io.joern.rubysrc2cpg.deprecated.utils.PackageTable
import io.joern.rubysrc2cpg.{Config, RubySrc2Cpg}
import io.joern.x2cpg.ValidationMode
import io.joern.x2cpg.testfixtures.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.{ICallResolver, NoResolve}
import org.scalatest.{Inside, Tag}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

trait RubyFrontend(
  useDeprecatedFrontend: Boolean,
  withDownloadDependencies: Boolean,
  disableFileContent: Boolean,
  antlrDebugging: Boolean,
  antlrProfiling: Boolean
) extends LanguageFrontend {
  override val fileSuffix: String = ".rb"

  implicit val config: Config =
    getConfig()
      .map(_.asInstanceOf[Config])
      .getOrElse(Config().withSchemaValidation(ValidationMode.Enabled))
      .withUseDeprecatedFrontend(useDeprecatedFrontend)
      .withDownloadDependencies(withDownloadDependencies)
      .withDisableFileContent(disableFileContent)
      .withAntlrDebugging(antlrDebugging)
      .withAntlrProfiling(antlrProfiling)

  override def execute(sourceCodeFile: File): Cpg = {
    new RubySrc2Cpg().createCpg(sourceCodeFile.getAbsolutePath).get
  }

}

class DefaultTestCpgWithRuby(
  packageTable: Option[PackageTable],
  useDeprecatedFrontend: Boolean,
  downloadDependencies: Boolean = false,
  disableFileContent: Boolean = true,
  antlrDebugging: Boolean = false,
  antlrProfiling: Boolean = false
) extends DefaultTestCpg
    with RubyFrontend(useDeprecatedFrontend, downloadDependencies, disableFileContent, antlrDebugging, antlrProfiling)
    with SemanticTestCpg {

  override protected def applyPasses(): Unit = {
    super.applyPasses()
    applyOssDataFlow()
  }

  override protected def applyPostProcessingPasses(): Unit = {
    packageTable match {
      case Some(table) =>
        RubySrc2Cpg.packageTableInfo.set(table)
      case None =>
    }
    RubySrc2Cpg.postProcessingPasses(this, config).foreach(_.createAndApply())
  }
}

class RubyCode2CpgFixture(
  withPostProcessing: Boolean = false,
  withDataFlow: Boolean = false,
  downloadDependencies: Boolean = false,
  disableFileContent: Boolean = true,
  extraFlows: List[FlowSemantic] = List.empty,
  packageTable: Option[PackageTable] = None,
  useDeprecatedFrontend: Boolean = false,
  antlrDebugging: Boolean = false,
  antlrProfiling: Boolean = false,
  semantics: Semantics = DefaultSemantics()
) extends Code2CpgFixture(() =>
      new DefaultTestCpgWithRuby(
        packageTable,
        useDeprecatedFrontend,
        downloadDependencies,
        disableFileContent,
        antlrDebugging,
        antlrProfiling
      )
        .withOssDataflow(withDataFlow)
        .withSemantics(semantics)
        .withPostProcessingPasses(withPostProcessing)
    )
    with Inside
    with SemanticCpgTestFixture(semantics) {

  implicit val resolver: ICallResolver = NoResolve

  protected def flowToResultPairs(path: Path): List[(String, Int)] =
    path.resultPairs().collect { case (firstElement, secondElement) =>
      (firstElement, secondElement.getOrElse(-1))
    }
}

class RubyCfgTestCpg(
  useDeprecatedFrontend: Boolean = true,
  downloadDependencies: Boolean = false,
  disableFileContent: Boolean = true,
  antlrDebugging: Boolean = false,
  antlrProfiling: Boolean = false
) extends CfgTestCpg
    with RubyFrontend(useDeprecatedFrontend, downloadDependencies, disableFileContent, antlrDebugging, antlrProfiling) {
  override val fileSuffix: String = ".rb"

}

/** Denotes a test which has been similarly ported to the new frontend.
  */
object SameInNewFrontend extends Tag("SameInNewFrontend")

/** Denotes a test which has been ported to the new frontend, but has different expectations.
  */
object DifferentInNewFrontend extends Tag("DifferentInNewFrontend")
