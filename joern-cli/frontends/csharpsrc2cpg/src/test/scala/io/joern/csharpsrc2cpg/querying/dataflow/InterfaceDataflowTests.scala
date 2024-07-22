package io.joern.csharpsrc2cpg.querying.dataflow

import io.joern.csharpsrc2cpg.testfixtures.CSharpCode2CpgFixture
import io.shiftleft.semanticcpg.language.*
import io.joern.dataflowengineoss.language.*

class InterfaceDataflowTests extends CSharpCode2CpgFixture(withDataFlow = true) {

  "simple interface dataflow" should {
    val cpg = code(
      """
        |namespace Anything.Any
        |{
        |    public class AnotherRepoImplementation : BaseRepo
        |    {
        |        private readonly DB _db;
        |
        |        public AnotherRepoImplementation(DB db)
        |        {
        |            _db = db;
        |        }
        |
        |        public override AnyValue fun(string x)
        |        {
        |            return _db.put(x);
        |        }
        |    }
        |}
        |""".stripMargin,
      "index.cs"
    )
      .moreCode(
        """
          |namespace Anything.Any
          |{
          |    public interface IRepo
          |    {
          |        AnyValue fun(string x);
          |    }
          |}
          |""".stripMargin,
        "IService.cs"
      )
      .moreCode(
        """
          |using Anything.Any;
          |
          |namespace Anything.ApiEndpoint
          |{
          |    public class EndpointClass
          |    {
          |        private readonly IRepo _repo;
          |
          |        public EndpointClass(IRepo repo)
          |        {
          |            _repo = repo;
          |        }
          |
          |        public AnyValue Get()
          |        {
          |            var firstName = "firstName";
          |            var any = _repo.fun(firstName);
          |            return any;
          |        }
          |    }
          |}
          |""".stripMargin,
        "service.cs"
      )
      .moreCode(
        """
          |namespace Anything.Any
          |{
          |    public abstract class BaseRepo : IRepo
          |    {
          |        public abstract AnyValue fun(string x);
          |    }
          |}
          |""".stripMargin,
        "BaseRepo.cs"
      )

    "find path from firstName to fun call" ignore {
      val src  = cpg.literal.codeExact("firstName").l
      val sink = cpg.call.nameExact("put")
      sink.reachableBy(src).size shouldBe 1
    }
  }
}
