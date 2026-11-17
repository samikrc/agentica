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

    // ── resolve: symlink escapes (real filesystem) ────────────────────────────

    test("resolve: symlink inside root pointing outside is rejected") {
        val dir     = java.nio.file.Files.createTempDirectory("sandbox-symlink-")
        val ws      = java.nio.file.Files.createDirectory(dir.resolve("workspace"))
        val outside = java.nio.file.Files.createDirectory(dir.resolve("outside"))
        java.nio.file.Files.writeString(outside.resolve("secret.txt"), "s3cr3t")
        java.nio.file.Files.createSymbolicLink(ws.resolve("link"), outside)

        assert(PathSandbox.resolve(ws.toString, "link/secret.txt") == Left("path_escaped"))
        assert(!PathSandbox.isWithin(ws.toString, "link/secret.txt"))
    }

    test("resolve: new file under a symlinked directory is rejected") {
        val dir     = java.nio.file.Files.createTempDirectory("sandbox-symlink-new-")
        val ws      = java.nio.file.Files.createDirectory(dir.resolve("workspace"))
        val outside = java.nio.file.Files.createDirectory(dir.resolve("outside"))
        java.nio.file.Files.createSymbolicLink(ws.resolve("link"), outside)

        // 'link/newfile.txt' does not exist yet — the symlinked parent must still be caught.
        assert(PathSandbox.resolve(ws.toString, "link/newfile.txt") == Left("path_escaped"))
    }

    test("resolve: dangling symlink pointing outside is rejected") {
        val dir     = java.nio.file.Files.createTempDirectory("sandbox-symlink-dangling-")
        val ws      = java.nio.file.Files.createDirectory(dir.resolve("workspace"))
        // 'link' dangles: its target does not exist, so Files.exists() misses it.
        java.nio.file.Files.createSymbolicLink(ws.resolve("link"), dir.resolve("missing-outside"))

        assert(PathSandbox.resolve(ws.toString, "link/pwned.txt") == Left("path_escaped"))
    }

    test("resolve: dangling symlink pointing inside root is allowed") {
        val dir = java.nio.file.Files.createTempDirectory("sandbox-symlink-dangling-in-")
        val ws  = java.nio.file.Files.createDirectory(dir.resolve("workspace"))
        java.nio.file.Files.createSymbolicLink(ws.resolve("link"), ws.resolve("missing-inside"))

        assert(PathSandbox.resolve(ws.toString, "link/new.txt").isRight)
    }

    test("resolve: symlink pointing inside root is allowed") {
        val dir    = java.nio.file.Files.createTempDirectory("sandbox-symlink-in-")
        val ws     = java.nio.file.Files.createDirectory(dir.resolve("workspace"))
        val inside = java.nio.file.Files.createDirectory(ws.resolve("real"))
        java.nio.file.Files.writeString(inside.resolve("ok.txt"), "fine")
        java.nio.file.Files.createSymbolicLink(ws.resolve("alias"), inside)

        assert(PathSandbox.resolve(ws.toString, "alias/ok.txt").isRight)
    }

    // ── isWithin convenience wrapper ──────────────────────────────────────────

    test("isWithin: returns true for in-sandbox path") {
        assert(PathSandbox.isWithin(root, "notes.txt"))
    }

    test("isWithin: returns false for escaping path") {
        assert(!PathSandbox.isWithin(root, "../../etc/hosts"))
    }
}
