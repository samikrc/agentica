package agentica.shell

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Paths

class PathSandboxTest extends AnyFunSuite
{

    private val root = "/home/user/workspace"

    // ── resolve: paths inside sandbox ────────────────────────────────────────

    test("resolve: simple relative path inside root") {
        val result = PathSandbox.resolve(root, "data/report.txt")
        assert(result.isRight)
        assert(result.toOption.get.toString == s"$root/data/report.txt")
    }

    test("resolve: nested relative path inside root") {
        val result = PathSandbox.resolve(root, "src/main/scala/Foo.scala")
        assert(result.isRight)
    }

    test("resolve: path at root itself") {
        val result = PathSandbox.resolve(root, "")
        // empty path resolves to root — allowed
        assert(result.isRight)
    }

    test("resolve: path with redundant dot segments that stays inside") {
        val result = PathSandbox.resolve(root, "data/../data/report.txt")
        assert(result.isRight)
        assert(result.toOption.get.toString == s"$root/data/report.txt")
    }

    // ── resolve: escape attempts ──────────────────────────────────────────────

    test("resolve: simple traversal escapes root") {
        val result = PathSandbox.resolve(root, "../escape.txt")
        assert(result == Left("path_escaped"))
    }

    test("resolve: deep traversal escapes root") {
        val result = PathSandbox.resolve(root, "data/../../../../etc/passwd")
        assert(result == Left("path_escaped"))
    }

    test("resolve: absolute path outside root") {
        val result = PathSandbox.resolve(root, "/etc/passwd")
        assert(result == Left("path_escaped"))
    }

    test("resolve: absolute path that IS the root") {
        val result = PathSandbox.resolve(root, root)
        assert(result.isRight)
    }

    test("resolve: sibling directory of root") {
        val result = PathSandbox.resolve(root, "../other_workspace/secret.txt")
        assert(result == Left("path_escaped"))
    }

    // ── isWithin convenience wrapper ──────────────────────────────────────────

    test("isWithin: returns true for in-sandbox path") {
        assert(PathSandbox.isWithin(root, "notes.txt"))
    }

    test("isWithin: returns false for escaping path") {
        assert(!PathSandbox.isWithin(root, "../../etc/hosts"))
    }
}
