package io.joern.jssrc2cpg.preprocessing

import io.joern.jssrc2cpg.Config
import io.joern.jssrc2cpg.testfixtures.JsSrc2CpgSuite
import io.joern.jssrc2cpg.utils.AstGenRunner
import io.shiftleft.semanticcpg.utils.FileUtil.*

import java.nio.file.{Files, Path}

class AstGenTests extends JsSrc2CpgSuite {

  "Ast gen" should {
    val tmpDir = Files.createTempDirectory("src")
    Seq("folder1", "folder2", "folder3").foreach { folder =>
      Files.writeString(Files.createDirectories(tmpDir / folder) / "1.js", s"console.log('$folder');")
    }

    def regularFiles(dir: Path): Set[String] = dir.walk().filter(Files.isRegularFile(_)).map(_.toString).toSet
    "ignore files mentioned in exclusion regex" in {
      val newInputDir = new AstGenRunner(Config().withInputPath(tmpDir.toString).withIgnoredFilesRegex(".*folder3.*"))
        .filterAndCopyFiles()

      val fileSet = regularFiles(newInputDir)
      fileSet.size shouldBe 2
      fileSet.count(_.matches(".*folder1.*")) shouldBe 1
      fileSet.count(_.matches(".*folder2.*")) shouldBe 1
      fileSet.count(_.matches(".*folder3.*")) shouldBe 0
    }

    "don't ignore files, if exclusion regex not passed" in {
      val newInputDir = new AstGenRunner(Config().withInputPath(tmpDir.toString)).filterAndCopyFiles()

      val fileSet = regularFiles(newInputDir)
      fileSet.size shouldBe 3
      fileSet.count(_.matches(".*folder1.*")) shouldBe 1
      fileSet.count(_.matches(".*folder2.*")) shouldBe 1
      fileSet.count(_.matches(".*folder3.*")) shouldBe 1

    }
  }

}
