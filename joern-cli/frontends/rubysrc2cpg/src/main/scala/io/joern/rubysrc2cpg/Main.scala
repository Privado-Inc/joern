package io.joern.rubysrc2cpg

import io.joern.rubysrc2cpg.Frontend.*
import io.joern.x2cpg.X2CpgConfig.GenericConfig
import io.joern.x2cpg.passes.frontend.{TypeRecoveryParserConfig, XTypeRecovery, XTypeRecoveryConfig}
import io.joern.x2cpg.typestub.TypeStubConfig
import io.joern.x2cpg.utils.server.FrontendHTTPServer
import io.joern.x2cpg.{X2CpgConfig, X2CpgMain}
import scopt.OParser

import java.nio.file.Paths

final case class Config(
  antlrCacheMemLimit: Double = 0.8d,
  useDeprecatedFrontend: Boolean = false,
  downloadDependencies: Boolean = false,
  antlrDebug: Boolean = false,
  antlrProfiling: Boolean = false,
  useTypeStubs: Boolean = true,
  override val genericConfig: GenericConfig = GenericConfig(defaultIgnoredFilesRegex =
    List("spec", "tests?", "vendor", "db(\\\\|/)([\\w_]*)migrate([_\\w]*)").flatMap { directory =>
      List(s"(^|\\\\)$directory($$|\\\\)".r.unanchored, s"(^|/)$directory($$|/)".r.unanchored)
    }
  ),
  override val typeRecoveryParserConfig: TypeRecoveryParserConfig.Config = TypeRecoveryParserConfig.Config()
) extends X2CpgConfig[Config]
    with TypeRecoveryParserConfig
    with TypeStubConfig {

  override def withGenericConfig(value: X2CpgConfig.GenericConfig): Config = copy(genericConfig = value)

  override def withTypeRecoveryParserConfig(value: TypeRecoveryParserConfig.Config): Config =
    copy(typeRecoveryParserConfig = value)

  def withAntlrCacheMemoryLimit(value: Double): Config = {
    copy(antlrCacheMemLimit = value)
  }

  def withUseDeprecatedFrontend(value: Boolean): Config = {
    copy(useDeprecatedFrontend = value)
  }

  def withAntlrDebugging(value: Boolean): Config = {
    copy(antlrDebug = value)
  }

  def withAntlrProfiling(value: Boolean): Config = {
    copy(antlrProfiling = value)
  }

  def withDownloadDependencies(value: Boolean): Config = {
    copy(downloadDependencies = value)
  }

  override def withTypeStubs(value: Boolean): Config = {
    copy(useTypeStubs = value)
  }
}

private object Frontend {

  implicit val defaultConfig: Config = Config()

  val cmdLineParser: OParser[Unit, Config] = {
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      programName("rubysrc2cpg"),
      opt[Double]("antlrCacheMemLimit")
        .hidden()
        .action((x, c) => c.withAntlrCacheMemoryLimit(x))
        .validate {
          case x if x < 0.3 =>
            failure(s"$x may result in too many evictions and reduce performance, try a value between 0.3 - 0.8.")
          case x if x > 0.8 =>
            failure(s"$x may result in too much memory usage and thrashing, try a value between 0.3 - 0.8.")
          case x =>
            success
        }
        .text("sets the heap usage threshold at which the ANTLR DFA cache is cleared during parsing (default 0.6)"),
      opt[Unit]("useDeprecatedFrontend")
        .action((_, c) => c.withUseDeprecatedFrontend(true))
        .text("uses the original (but deprecated) Ruby frontend (default false)"),
      opt[Unit]("antlrDebug")
        .hidden()
        .action((_, c) => c.withAntlrDebugging(true)),
      opt[Unit]("antlrProfile")
        .hidden()
        .action((_, c) => c.withAntlrProfiling(true)),
      opt[Unit]("enable-file-content")
        .action((_, c) => c.withDisableFileContent(false))
        .text("Enable file content"),
      XTypeRecoveryConfig.parserOptionsForParserConfig,
      TypeStubConfig.parserOptions
    )
  }
}

object Main extends X2CpgMain(new RubySrc2Cpg(), cmdLineParser)
