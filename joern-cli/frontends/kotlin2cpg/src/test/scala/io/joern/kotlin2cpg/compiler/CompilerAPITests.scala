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
        if (scala.util.Properties.isWin) Seq("cmd.exe", "/C", "gradlew.bat", "gatherDependencies")
        else Seq("./gradlew", "gatherDependencies")
      ExternalCommand.run(command, projectDirPath).toTry shouldBe Symbol("success")
      val config = Config(classpath = Set(projectDependenciesPath.toString))
      val cpg = new Kotlin2Cpg().createCpg(projectDirPath)(config).getOrElse {
        fail("Could not create a CPG!")
      }
      cpg.method.fullName(s".*${Defines.UnresolvedNamespace}.*") shouldBe empty
      cpg.method.signature(s".*${Defines.UnresolvedNamespace}.*") shouldBe empty
    }

    "should return all the individual folder name" in {
      val paths = ContentSourcesPicker.dirsForRoot(projectDirPath, Config().withInputPath(projectDirPath))
      paths.size shouldBe 26
    }

    "should return all the individual folder name excluding paths, mentioned in exclusion regex" in {
      import java.io.File
      val paths = ContentSourcesPicker.dirsForRoot(
        projectDirPath,
        Config().withInputPath(projectDirPath).withIgnoredFilesRegex(s".*test.*")
      )
      paths.size shouldBe 21
    }

    "should return all the individual folder name excluding paths, when no-resolve-type" in {
      val paths =
        ContentSourcesPicker.dirsForRoot(projectDirPath, Config().withInputPath(projectDirPath).withResolveTypes(false))

      paths.size shouldBe 1
      paths.head shouldBe projectDirPath
    }

  }
}
