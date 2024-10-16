package io.joern.kotlin2cpg.compiler

import better.files.File
import io.joern.kotlin2cpg.Config
import io.joern.kotlin2cpg.DefaultContentRootJarPath
import io.joern.kotlin2cpg.Kotlin2Cpg
import io.joern.kotlin2cpg.types.ContentSourcesPicker
import io.joern.x2cpg.utils.ExternalCommand
import io.joern.x2cpg.Defines
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.utils.ProjectRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.nio.file.Paths

class CompilerAPITests extends AnyFreeSpec with Matchers {

  class ErrorCountMessageCollector extends MessageCollector {
    private val logger = LoggerFactory.getLogger(getClass)

    var errorCount = 0
    override def report(
      compilerMessageSeverity: CompilerMessageSeverity,
      message: String,
      compilerMessageSourceLocation: CompilerMessageSourceLocation
    ): Unit = {
      if (compilerMessageSeverity.isError) {
        logger.debug(s"Received message from compiler: $message")
        errorCount += 1
      }
    }
    override def hasErrors: Boolean = errorCount != 0
    override def clear(): Unit      = {}
  }

  "KotlinCoreEnvironment generation on simple test code which calls external libraries" - {
    val projectDirPath          = ProjectRoot.relativise("joern-cli/frontends/kotlin2cpg/src/test/resources/code/ktmin")
    val projectDependenciesPath = Paths.get(projectDirPath, "dependencies")

    "should not receive a compiler error message when the dependencies of the project have been provided" in {
      val jarResources = Seq(
        DefaultContentRootJarPath("jars/kotlin-stdlib-1.9.0.jar", isResource = true),
        DefaultContentRootJarPath("jars/kotlin-stdlib-common-1.9.0.jar", isResource = true),
        DefaultContentRootJarPath("jars/kotlin-stdlib-jdk8-1.9.0.jar", isResource = true)
      )

      val defaultContentRootJarsDir = File(projectDependenciesPath)
      val contentRoots = defaultContentRootJarsDir.listRecursively
        .filter(_.pathAsString.endsWith("jar"))
        .map { f => DefaultContentRootJarPath(f.pathAsString, false) }
        .toSeq ++ jarResources
      val messageCollector = new ErrorCountMessageCollector()
      val environment      = CompilerAPI.makeEnvironment(Seq(projectDirPath), Seq(), contentRoots, messageCollector)

      KotlinToJVMBytecodeCompiler.INSTANCE.analyze(environment)
      messageCollector.hasErrors() shouldBe false
    }

    "should receive a compiler error message when the dependencies of the project have not been provided" in {
      val messageCollector = new ErrorCountMessageCollector()
      val environment      = CompilerAPI.makeEnvironment(Seq(projectDirPath), Seq(), Seq(), messageCollector)

      KotlinToJVMBytecodeCompiler.INSTANCE.analyze(environment)
      messageCollector.hasErrors() shouldBe true
    }
  }

  "KotlinCoreEnvironment generation on springboot-kotlin-webgoat" - {
    val projectDirPath =
      ProjectRoot.relativise("joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat")
    val projectDependenciesPath = Paths.get(projectDirPath, "build", "gatheredDependencies")

    "should not contain methods with unresolved types/namespaces" in {
      val command =
        if (scala.util.Properties.isWin) "cmd.exe /C gradlew.bat gatherDependencies" else "./gradlew gatherDependencies"
      ExternalCommand.run(command, projectDirPath) shouldBe Symbol("success")
      val config = Config(classpath = Set(projectDependenciesPath.toString))
      val cpg = new Kotlin2Cpg().createCpg(projectDirPath)(config).getOrElse {
        fail("Could not create a CPG!")
      }
      cpg.method.fullName(s".*${Defines.UnresolvedNamespace}.*") shouldBe empty
      cpg.method.signature(s".*${Defines.UnresolvedNamespace}.*") shouldBe empty
    }

    "should return all the individual folder name" in {
      val paths = ContentSourcesPicker.dirsForRoot(projectDirPath, Config().withInputPath(projectDirPath))
      paths shouldBe List(
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/gradle",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/gradle/wrapper",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/vcs-1",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/buildOutputCleanup",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/executionHistory",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/fileChanges",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/dependencies-accessors",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/checksums",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/vcsMetadata",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/fileHashes",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/build",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/build/gatheredDependencies",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/test",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/test/kotlin",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/test/kotlin/ai",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/test/kotlin/ai/qwiet",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/test/kotlin/ai/qwiet/springbootkotlinwebgoat",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/resources",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin/ai",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin/ai/qwiet",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin/ai/qwiet/springbootkotlinwebgoat"
      )
    }

    "should return all the individual folder name excluding paths, mentioned in exclusion regex" in {
      val paths = ContentSourcesPicker.dirsForRoot(
        projectDirPath,
        Config().withInputPath(projectDirPath).withIgnoredFilesRegex(".*/test/.*")
      )
      paths shouldBe List(
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/gradle",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/gradle/wrapper",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/vcs-1",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/buildOutputCleanup",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/executionHistory",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/fileChanges",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/dependencies-accessors",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/checksums",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/vcsMetadata",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/.gradle/7.6.1/fileHashes",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/build",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/build/gatheredDependencies",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/test",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/resources",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin/ai",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin/ai/qwiet",
        "/Users/khemrajrathore/Privado/niagara/joern/joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat/src/main/kotlin/ai/qwiet/springbootkotlinwebgoat"
      )
    }

    "should return all the individual folder name excluding paths, when no-resolve-type" in {
      val paths =
        ContentSourcesPicker.dirsForRoot(projectDirPath, Config().withInputPath(projectDirPath).withResolveTypes(false))

      paths shouldBe List("./../../../joern-cli/frontends/kotlin2cpg/src/test/resources/code/springboot-kotlin-webgoat")
    }

  }
}
