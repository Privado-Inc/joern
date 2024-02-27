package io.joern.csharpsrc2cpg.querying.ast

import io.joern.csharpsrc2cpg.testfixtures.CSharpCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class Playground extends CSharpCode2CpgFixture {
  "playground" should {
    val cpg = code("""
        |using System.Web;
        |
        |namespace Foo {
        | public class Bar {
        |   public static void Main() {
        |   var query = HttpUtility.ParseQueryString(builder.Query);
        | }
        | }
        |}
        |
        |""".stripMargin)

    "resolve type" in {
      println(cpg.call("ParseQueryString").methodFullName.l)
    }
  }
}
