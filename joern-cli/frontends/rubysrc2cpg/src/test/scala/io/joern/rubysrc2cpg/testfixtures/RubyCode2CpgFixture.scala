package io.joern.rubysrc2cpg.testfixtures

import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.language.Path
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.joern.dataflowengineoss.testfixtures.{SemanticCpgTestFixture, SemanticTestCpg}
import io.joern.rubysrc2cpg.deprecated.utils.PackageTable
import io.joern.rubysrc2cpg.{Config, RubySrc2Cpg}
import io.joern.rubysrc2cpg.parser.RubyAstGenRunner.JRubyEnvironment
import io.joern.x2cpg.ValidationMode
import io.joern.x2cpg.frontendspecific.rubysrc2cpg
import io.joern.x2cpg.testfixtures.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.{ICallResolver, NoResolve}
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.shiftleft.semanticcpg.validation.{PostFrontendValidator, ValidationLevel}
import org.scalatest.{Inside, Tag}

import java.io.File
import java.nio.charset.Charset
import scala.util.Using

trait RubyFrontend(
  useDeprecatedFrontend: Boolean,
  disableFileContent: Boolean,
  antlrDebugging: Boolean,
  antlrProfiling: Boolean
) extends LanguageFrontend {
  override type ConfigType = Config

  override val fileSuffix: String = ".rb"

  protected def sharedJRubyEnv: Option[JRubyEnvironment] = None

  implicit val config: Config =
    getConfig()
      .getOrElse(Config().withSchemaValidation(ValidationMode.Enabled))
      .withUseDeprecatedFrontend(useDeprecatedFrontend)
      .withDisableFileContent(disableFileContent)
      .withAntlrDebugging(antlrDebugging)
      .withAntlrProfiling(antlrProfiling)

  override def execute(sourceCodeFile: File): Cpg = {
    val tmp =
      Using.resource(new RubySrc2Cpg(sharedJRubyEnv))(
        _.createCpg(config.withInputPath(sourceCodeFile.getAbsolutePath)).get
      )
    new PostFrontendValidator(tmp, if (useDeprecatedFrontend) ValidationLevel.V0 else ValidationLevel.V3).run()
    tmp
  }

}

class DefaultTestCpgWithRuby(
  packageTable: Option[PackageTable],
  useDeprecatedFrontend: Boolean,
  disableFileContent: Boolean = true,
  antlrDebugging: Boolean = false,
  antlrProfiling: Boolean = false
) extends DefaultTestCpg
    with RubyFrontend(useDeprecatedFrontend, disableFileContent, antlrDebugging, antlrProfiling)
    with SemanticTestCpg {

  private var _sharedJRubyEnv: Option[JRubyEnvironment] = None

  def withSharedJRubyEnv(env: JRubyEnvironment): this.type = {
    _sharedJRubyEnv = Some(env)
    this
  }

  override protected def sharedJRubyEnv: Option[JRubyEnvironment] = _sharedJRubyEnv

  override protected def applyPasses(): Unit = {
    super.applyPasses()
    applyOssDataFlow()
  }

  override protected def applyPostProcessingPasses(): Unit = {
    if (useDeprecatedFrontend) {
      packageTable.foreach(RubySrc2Cpg.packageTableInfo.set)
      RubySrc2Cpg.deprecatedPostProcessingPasses(this).foreach(_.createAndApply())
    } else {
      new rubysrc2cpg.RubyPostProcessing().run(new LayerCreatorContext(this))
    }
  }
}

class RubyCode2CpgFixture(
  withPostProcessing: Boolean = false,
  withDataFlow: Boolean = false,
  disableFileContent: Boolean = true,
  packageTable: Option[PackageTable] = None,
  useDeprecatedFrontend: Boolean = false,
  antlrDebugging: Boolean = false,
  antlrProfiling: Boolean = false,
  semantics: Semantics = DefaultSemantics()
) extends Code2CpgFixture(() =>
      new DefaultTestCpgWithRuby(
        packageTable,
        useDeprecatedFrontend,
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

  private lazy val _sharedJRubyEnv = JRubyEnvironment()

  override def code(code: String): DefaultTestCpgWithRuby = {
    super.code(code).withSharedJRubyEnv(_sharedJRubyEnv)
  }

  override def code(code: String, fileName: String): DefaultTestCpgWithRuby = {
    super.code(code, fileName).withSharedJRubyEnv(_sharedJRubyEnv)
  }

  override def code(code: String, fileName: String, charset: Charset): DefaultTestCpgWithRuby = {
    super.code(code, fileName, charset).withSharedJRubyEnv(_sharedJRubyEnv)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    _sharedJRubyEnv.close()
  }

  implicit val resolver: ICallResolver = NoResolve

  protected def flowToResultPairs(path: Path): List[(String, Int)] =
    path.resultPairs().collect { case (firstElement, secondElement) =>
      (firstElement, secondElement.getOrElse(-1))
    }
}

class RubyCfgTestCpg(
  useDeprecatedFrontend: Boolean = false,
  disableFileContent: Boolean = true,
  antlrDebugging: Boolean = false,
  antlrProfiling: Boolean = false
) extends CfgTestCpg
    with RubyFrontend(useDeprecatedFrontend, disableFileContent, antlrDebugging, antlrProfiling) {
  override val fileSuffix: String = ".rb"

}

/** Denotes a test which has been similarly ported to the new frontend.
  */
object SameInNewFrontend extends Tag("SameInNewFrontend")

/** Denotes a test which has been ported to the new frontend, but has different expectations.
  */
object DifferentInNewFrontend extends Tag("DifferentInNewFrontend")
