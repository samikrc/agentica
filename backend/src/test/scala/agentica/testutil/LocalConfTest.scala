package agentica.testutil

import org.scalatest.funsuite.AnyFunSuite

class LocalConfTest extends AnyFunSuite
{
    test("parse reads key=value pairs and ignores comments and blank lines") {
        val parsed = LocalConf.parse(
            """# comment
              |
              |LM_STUDIO_URL=http://localhost:1234
              |EMPTYISH=value
              |not a pair
              |""".stripMargin)
        assert(parsed == Map("LM_STUDIO_URL" -> "http://localhost:1234", "EMPTYISH" -> "value"))
    }

    test("parse strips matching quotes and preserves inner spaces") {
        val parsed = LocalConf.parse("A=\"two words\"\nB='single'\nC=\"mismatched'\n")
        assert(parsed("A") == "two words")
        assert(parsed("B") == "single")
        assert(parsed("C") == "\"mismatched'")
    }

    test("parse keeps values containing '=' and lets later duplicates win") {
        val parsed = LocalConf.parse("KEY=first\nURL=http://host:1234/path?x=1\nKEY=second\n")
        assert(parsed("KEY") == "second")
        assert(parsed("URL") == "http://host:1234/path?x=1")
    }
}
