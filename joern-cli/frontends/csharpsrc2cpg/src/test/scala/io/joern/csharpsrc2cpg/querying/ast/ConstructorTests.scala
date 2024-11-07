package io.joern.csharpsrc2cpg.querying.ast

import io.joern.csharpsrc2cpg.testfixtures.CSharpCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class ConstructorTests extends CSharpCode2CpgFixture {

  "Method Getter and Setter" should {

    val cpg = code(
      """
        |using System;
        |namespace Main
        |{
        |    public class User {
        |        private string phone_number;
        |        public string PhoneNumber {
        |            get {
        |                return this.phone_number;
        |            }
        |            set {
        |                this.phone_number = value;
        |                val name = aws.put(value);
        |                val firstName = "some";
        |            }
        |        }
        |    }
        |
        |    class Program {
        |        static void Main(string[] args) {
        |            User user = new User();
        |            user.PhoneNumber = "123-456-7890";
        |        }
        |    }
        |}
        |""".stripMargin)

    "correctly resolve method" in {
      val List(method) = cpg.method.name("PhoneNumber").l
      method.size shouldbe 1
      method.code shouldBe "gg"
    }
  }

}
