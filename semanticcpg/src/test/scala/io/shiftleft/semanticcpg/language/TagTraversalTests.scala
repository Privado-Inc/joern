package io.shiftleft.semanticcpg.language

import io.shiftleft.semanticcpg.testing.MockCpg
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TagTraversalTests extends AnyWordSpec with Matchers {

  "the tag step on a node" should {
    val cpg = MockCpg()
      .withMetaData()
      .withMethod("foo")
      .withTagsOnMethod("foo", methodTags = List(("k", "v"), ("k", "v"), ("k", "w")))
      .cpg

    "return each (name, value) pair once" in {
      cpg.method.nameExact("foo").head.tag.map(tag => (tag.name, tag.value)).l.sorted shouldBe List(
        ("k", "v"),
        ("k", "w")
      )
    }
  }
}
