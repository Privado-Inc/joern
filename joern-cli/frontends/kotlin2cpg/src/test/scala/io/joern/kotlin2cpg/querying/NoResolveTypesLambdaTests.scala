package io.joern.kotlin2cpg.querying

import io.joern.kotlin2cpg.Config
import io.joern.kotlin2cpg.testfixtures.KotlinCode2CpgFixture
import io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn
import io.shiftleft.semanticcpg.language.*

class NoResolveTypesLambdaTests extends KotlinCode2CpgFixture(withOssDataflow = false) {
  "CPG for a lambda with an explicit parameter that shadows an outer one when type resolution is disabled" should {
    val cpg = code("""
        |fun f1(x: Int, y: Int) {
        |  val g = { x: Int -> x + y }
        |}
        |""".stripMargin).withConfig(Config().withResolveTypes(false))

    "have a closure binding for the captured variable only" in {
      cpg.closureBinding.closureBindingId.l.map(_.split('.').last) shouldBe List("y")
    }

    "have the lambda body identifier refer to the lambda's own parameter" in {
      val List(identifier) = cpg.identifier.nameExact("x").l
      identifier.refsTo.collectAll[MethodParameterIn].method.l shouldBe List(identifier.method)
    }
  }

  "CPG for a lambda with an implicit `it` parameter that shadows an outer one when type resolution is disabled" should {
    val cpg = code("""
        |fun f2(it: Int, y: Int) {
        |  listOf(1).map { it + y }
        |}
        |""".stripMargin).withConfig(Config().withResolveTypes(false))

    "have a closure binding for the captured variable only" in {
      cpg.closureBinding.closureBindingId.l.map(_.split('.').last) shouldBe List("y")
    }

    "have the lambda body identifier refer to the lambda's own parameter" in {
      val List(identifier) = cpg.identifier.nameExact("it").l
      identifier.refsTo.collectAll[MethodParameterIn].method.l shouldBe List(identifier.method)
    }
  }
}
