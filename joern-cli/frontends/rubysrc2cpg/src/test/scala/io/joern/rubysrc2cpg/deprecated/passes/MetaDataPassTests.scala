package io.joern.rubysrc2cpg.deprecated.passes

import io.joern.rubysrc2cpg.{Config, RubySrc2Cpg}
import io.shiftleft.codepropertygraph.generated.Languages
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.utils.FileUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class MetaDataPassTests extends AnyWordSpec with Matchers {

  "MetaDataPass" should {

    "create a metadata node with correct language" in {
      FileUtil.usingTemporaryDirectory("rubysrc2cpgTest") { dir =>
        val config = Config()
          .withInputPath(Files.createFile(dir.resolve("dummyinputfile")).toString)
          .withOutputPath(Files.createFile(dir.resolve("dummyoutputfile")).toString)
        val cpg = new RubySrc2Cpg().createCpg(config).get
        cpg.metaData.language.l shouldBe List(Languages.RUBYSRC)
      }
    }
  }
}
