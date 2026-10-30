package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

class MetaClassCallHandlerAstTests extends PySrc2CpgFixture with Matchers {
  val cpg = code("""class Foo:
                   |  def __init__(self, a):
                   |    self.a = a
                   |""".stripMargin)

  "every identifier has an AST parent" in {
    cpg.identifier.filter(_._astIn.isEmpty).code.l shouldBe empty
  }
}
