package io.joern.kotlin2cpg.compiler

import better.files.*
import io.joern.kotlin2cpg.types.ContentSourcesPicker
import io.joern.kotlin2cpg.{Config, DefaultContentRootJarPath, Kotlin2Cpg}
import io.joern.x2cpg.Defines
import io.joern.x2cpg.testfixtures.SourceCodeFixture
import io.joern.x2cpg.utils.ExternalCommand
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.utils.ProjectRoot
import org.jetbrains.kotlin.cli.common.messages.{
  CompilerMessageSeverity,
  CompilerMessageSourceLocation,
  MessageCollector
}
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.slf4j.LoggerFactory

import java.nio.file.Paths

class CompilerAPITests extends SourceCodeFixture {

  def setupRepoStructure(files: List[(String, String)]): String = {
    files
      .foldLeft(emptyWriter) { case (writer, (code, fileName)) => writer.moreCode(code, fileName) }
      .writeCode(".kt")
      .toString
  }

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

  "Kotlin module detection test" should {
    "simple repo structure without gradle file" in {
      val repoDir =
        setupRepoStructure(List(("first", "root/A.kt"), ("second", "root/B.kt"), ("third", "root/C.kt")))
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 1
      modules.head.modulePathRoot shouldBe repoDir
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/root")
    }

    "repo structure without gradle file with source in multiple directories" in {
      val repoDir =
        setupRepoStructure(List(("first", "dir1/dir2/A.kt"), ("second", "dir3/B.kt"), ("third", "dir4/dir5/dir6/C.kt")))
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 1
      modules.head.modulePathRoot shouldBe repoDir
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/dir4/dir5/dir6", s"$repoDir/dir3", s"$repoDir/dir1/dir2")
    }

    "repo structure with single build.gradle file" in {
      val repoDir =
        setupRepoStructure(List(("build", "build.gradle"), ("second", "dir3/B.kt"), ("third", "dir4/dir5/dir6/C.kt")))
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 1
      modules.head.modulePathRoot shouldBe repoDir
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/dir4/dir5/dir6", s"$repoDir/dir3")
    }

    "repo structure with single build.gradle.kts file" in {
      val repoDir =
        setupRepoStructure(
          List(("build", "build.gradle.kts"), ("second", "dir3/B.kt"), ("third", "dir4/dir5/dir6/C.kt"))
        )
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 1
      modules.head.modulePathRoot shouldBe repoDir
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/dir4/dir5/dir6", s"$repoDir/dir3")
    }

    "repo structure with single build.gradle.kts file inside directory" in {
      val repoDir =
        setupRepoStructure(
          List(("build", "dir1/build.gradle.kts"), ("second", "dir1/dir3/B.kt"), ("third", "dir1/dir4/dir5/dir6/C.kt"))
        )
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 1
      modules.head.modulePathRoot shouldBe s"$repoDir/dir1"
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/dir1/dir4/dir5/dir6", s"$repoDir/dir1/dir3")
    }

    "repo structure with multiple child modules" in {
      val repoDir = setupRepoStructure(
        List(
          ("build", "dir1/build.gradle.kts"),
          ("second", "dir1/dir3/B.kt"),
          ("third", "dir1/dir4/dir5/dir6/C.kt"),
          ("fourth", "dir2/build.gradle.kts"),
          ("fifth", "dir2/dir7/D.kt")
        )
      )
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 2
      modules.head.modulePathRoot shouldBe s"$repoDir/dir2"
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/dir2/dir7")
      modules(1).modulePathRoot shouldBe s"$repoDir/dir1"
      modules(1).sourceFileDirs shouldBe Seq(s"$repoDir/dir1/dir4/dir5/dir6", s"$repoDir/dir1/dir3")
    }

    "repo with parent module along with multiple child modules" in {
      val repoDir = setupRepoStructure(
        List(
          ("build", "dir1/build.gradle.kts"),
          ("second", "dir1/dir3/B.kt"),
          ("third", "dir1/dir4/dir5/dir6/C.kt"),
          ("fourth", "dir2/build.gradle.kts"),
          ("fifth", "dir2/dir7/D.kt"),
          ("sixth", "build.gradle.kts")
        )
      )
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 2
      modules.head.modulePathRoot shouldBe s"$repoDir/dir2"
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/dir2/dir7")
      modules(1).modulePathRoot shouldBe s"$repoDir/dir1"
      modules(1).sourceFileDirs shouldBe Seq(s"$repoDir/dir1/dir4/dir5/dir6", s"$repoDir/dir1/dir3")
    }

    "repo with parent module along with source code and multiple child modules" in {
      val repoDir = setupRepoStructure(
        List(
          ("build", "dir1/build.gradle.kts"),
          ("second", "dir1/dir3/B.kt"),
          ("third", "dir1/dir4/dir5/dir6/C.kt"),
          ("fourth", "dir2/build.gradle.kts"),
          ("fifth", "dir2/dir7/D.kt"),
          ("sixth", "build.gradle.kts"),
          ("seventh", "src/main/java/A.kt")
        )
      )
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 3
      modules.head.modulePathRoot shouldBe s"$repoDir"
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/src/main/java")
      modules(1).modulePathRoot shouldBe s"$repoDir/dir2"
      modules(1).sourceFileDirs shouldBe Seq(s"$repoDir/dir2/dir7")
      modules(2).modulePathRoot shouldBe s"$repoDir/dir1"
      modules(2).sourceFileDirs shouldBe Seq(s"$repoDir/dir1/dir4/dir5/dir6", s"$repoDir/dir1/dir3")
    }
    "invalid case where build.gradle file present inside source directory" in {
      val repoDir = setupRepoStructure(
        List(
          ("build", "dir1/build.gradle.kts"),
          ("second", "dir1/dir3/B.kt"),
          ("third", "dir1/dir4/dir5/dir6/C.kt"),
          ("fourth", "src/main/java/some.kt"),
          ("fourth", "src/main/java/dir8/build.gradle.kts"),
          ("fifth", "src/main/java/dir7/D.kt"),
          ("sixth", "build.gradle.kts")
        )
      )
      val modules = ContentSourcesPicker.getModuleWiseSegregation(repoDir, Config().withInputPath(repoDir))
      modules.size shouldBe 2
      modules.head.modulePathRoot shouldBe s"$repoDir"
      modules.head.sourceFileDirs shouldBe Seq(s"$repoDir/src/main/java", s"$repoDir/src/main/java/dir7")
      modules(1).modulePathRoot shouldBe s"$repoDir/dir1"
      modules(1).sourceFileDirs shouldBe Seq(s"$repoDir/dir1/dir4/dir5/dir6", s"$repoDir/dir1/dir3")
    }
  }

  "KotlinCoreEnvironment generation on simple test code which calls external libraries" should {
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
      val (environments, environment) =
        CompilerAPI.makeEnvironment(Seq(projectDirPath), Seq(), contentRoots, messageCollector)

      KotlinToJVMBytecodeCompiler.INSTANCE.analyze(environment)
      messageCollector.hasErrors() shouldBe false
    }

    "should receive a compiler error message when the dependencies of the project have not been provided" in {
      val messageCollector            = new ErrorCountMessageCollector()
      val (environments, environment) = CompilerAPI.makeEnvironment(Seq(projectDirPath), Seq(), Seq(), messageCollector)

      KotlinToJVMBytecodeCompiler.INSTANCE.analyze(environment)
      messageCollector.hasErrors() shouldBe true
    }
  }

  "KotlinCoreEnvironment generation on springboot-kotlin-webgoat" should {
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
