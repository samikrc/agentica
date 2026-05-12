package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{MemoryStore, Session}
import agentica.shell.{PathSandbox, SessionScratchpad}
import agentica.tools.{ExecutionContext, ToolResult, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.SynchronousQueue

/**
 *  Verifies that all `files.*` tools enforce the workspace sandbox at the `execute` stage.
 *
 *  These are tool-level tests — they exercise the full `execute → render` pipeline
 *  to confirm that a path-traversal attempt results in a `path_escaped` error
 *  response rather than actual filesystem access.
 *
 *  PathSandbox unit-level tests live in [[agentica.shell.PathSandboxTest]].
 */
class FilesToolSandboxTest extends AnyFunSuite
{

    /** Workspace root used for all tests. */
    private val root = "/tmp/agentica-sandbox-test-workspace"

    /** Minimal stub ScopeStore that grants nothing (all permission checks return false). */
    private val noGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = false
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /**
     *  Builds a minimal [[ExecutionContext]] backed by the given workspace root.
     *  @param rootPath  Absolute path to the workspace root.
     *  @return          A stub context sufficient for sandbox tests.
     */
    private def ctx(rootPath: String): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(rootPath)),
            traceId         = "sandbox-test",
            scopeStore      = noGrants,
            scratchpad      = SessionScratchpad(),
            memoryStore     = null,
            onEvent         = _ => (),
            permissionLatch = SynchronousQueue[GrantDecision]()
        )

    // ── files.stat ────────────────────────────────────────────────────────────

    test("files.stat: traversal via ../ yields path_escaped error") {
        val input  = FilesStatInput("../../etc/passwd")
        val output = FilesStat.execute(input, ctx(root))
        assert(output.error.contains("path_escaped"))
    }

    test("files.stat: absolute path outside root yields path_escaped error") {
        val input  = FilesStatInput("/etc/shadow")
        val output = FilesStat.execute(input, ctx(root))
        assert(output.error.contains("path_escaped"))
    }

    test("files.stat: render converts path_escaped output to Err ToolResult") {
        val output = FilesStatOutput("../../etc/passwd", 0, "", "", Some("path_escaped"))
        val result = FilesStat.render(output)
        assert(result.status.isInstanceOf[ToolStatus.Err])
        val err = result.status.asInstanceOf[ToolStatus.Err]
        assert(err.code == "path_escaped")
    }

    test("files.stat: sibling workspace directory is rejected") {
        val input  = FilesStatInput("../other-workspace/secret.txt")
        val output = FilesStat.execute(input, ctx(root))
        assert(output.error.contains("path_escaped"))
    }

    // ── files.read ────────────────────────────────────────────────────────────

    test("files.read: traversal via ../ yields path_escaped in content sentinel") {
        val input  = FilesReadInput(java.nio.file.Paths.get("../../etc/passwd"), None)
        val output = FilesRead.execute(input, ctx(root))
        assert(output.error.contains(FilesReadError.PathEscaped))
    }

    test("files.read: absolute path outside root yields path_escaped in content sentinel") {
        val input  = FilesReadInput(java.nio.file.Paths.get("/etc/shadow"), None)
        val output = FilesRead.execute(input, ctx(root))
        assert(output.error.contains(FilesReadError.PathEscaped))
    }

    test("files.read: render converts path_escaped sentinel to Err ToolResult") {
        val output = FilesReadOutput("", 0, 0, false, "../../etc/passwd", 0, Some(FilesReadError.PathEscaped))
        val result = FilesRead.render(output)
        assert(result.status.isInstanceOf[ToolStatus.Err])
    }

    // ── files.write ───────────────────────────────────────────────────────────

    test("files.write: traversal via ../ yields path_escaped before permission check") {
        val input  = FilesWriteInput("../../tmp/injected.txt", "malicious content")
        val output = FilesWrite.execute(input, ctx(root))
        // Must short-circuit at sandbox — never reach permission latch
        assert(output.error.contains(FilesWriteError.PathEscaped))
    }

    test("files.write: absolute path outside root yields path_escaped") {
        val input  = FilesWriteInput("/tmp/injected.txt", "content")
        val output = FilesWrite.execute(input, ctx(root))
        assert(output.error.contains(FilesWriteError.PathEscaped))
    }

    test("files.write: render converts path_escaped output to Err ToolResult") {
        val output = FilesWriteOutput("../../tmp/injected.txt", 0, Some(FilesWriteError.PathEscaped))
        val result = FilesWrite.render(output)
        assert(result.status.isInstanceOf[ToolStatus.Err])
        val err = result.status.asInstanceOf[ToolStatus.Err]
        assert(err.code == "path_escaped")
    }

    // ── files.list ────────────────────────────────────────────────────────────

    test("files.list: traversal via ../ yields path_escaped error") {
        val input  = FilesListInput("../../etc", recursive = false, all = false, depth = 1, pattern = None)
        val output = FilesList.execute(input, ctx(root))
        assert(output.error.contains("path_escaped"))
    }

    test("files.list: absolute path outside root yields path_escaped error") {
        val input  = FilesListInput("/etc", recursive = false, all = false, depth = 1, pattern = None)
        val output = FilesList.execute(input, ctx(root))
        assert(output.error.contains("path_escaped"))
    }

    // ── files.search ──────────────────────────────────────────────────────────

    test("files.search: traversal via ../ yields path_escaped error") {
        val input  = FilesSearchInput(
            rawPath      = "../../etc",
            query        = "password",
            recursive    = true,
            ignoreCase   = false,
            linesContext = 0,
            maxMatches   = 10,
            include      = None,
            useRegex     = false
        )
        val output = FilesSearch.execute(input, ctx(root))
        assert(output.error.contains(FilesSearchError.PathEscaped))
    }

    test("files.search: absolute path outside root yields path_escaped error") {
        val input  = FilesSearchInput(
            rawPath      = "/etc",
            query        = "password",
            recursive    = true,
            ignoreCase   = false,
            linesContext = 0,
            maxMatches   = 10,
            include      = None,
            useRegex     = false
        )
        val output = FilesSearch.execute(input, ctx(root))
        assert(output.error.contains(FilesSearchError.PathEscaped))
    }

    // ── PathSandbox: normalised-path edge cases ───────────────────────────────

    test("resolve: path with redundant ../ that stays inside root is allowed") {
        // e.g. src/../src/main.scala — normalises to src/main.scala, still inside
        val result = PathSandbox.resolve(root, "src/../src/main.scala")
        assert(result.isRight)
    }

    test("resolve: URL-encoded traversal is not decoded — treated as literal filename") {
        // PathSandbox uses java.nio, which does NOT decode percent-encoding.
        // %2F is not a path separator; the literal '%2F' string stays inside root.
        val result = PathSandbox.resolve(root, "data%2F..%2F..%2Fetc%2Fpasswd")
        assert(result.isRight, "percent-encoded sequences are literal filenames, not separators")
    }

    test("resolve: null-byte in path is not a traversal but is a malformed path") {
        // Java's Path rejects null bytes with an InvalidPathException — caught as Left.
        val result = scala.util.Try(PathSandbox.resolve(root, "file\u0000.txt"))
        assert(result.isFailure || result.get == Left("path_escaped"),
            "null byte in path must not succeed")
    }
}
