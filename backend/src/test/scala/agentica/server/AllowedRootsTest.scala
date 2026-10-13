package agentica.server

import org.scalatest.funsuite.AnyFunSuite
import java.io.File
import java.nio.file.Paths

class AllowedRootsTest extends AnyFunSuite
{
    private val home    = System.getProperty("user.home")
    private val isWin   = System.getProperty("os.name").toLowerCase.contains("win")
    private val sep     = File.pathSeparator
    private val outside = if (isWin) "C:\\Windows" else "/etc"

    // ── roots: env parsing ───────────────────────────────────────────────────

    test("roots: unset env defaults to user.home") {
        assert(AllowedRoots.roots(None) == List(Paths.get(home).toAbsolutePath.normalize()))
    }

    test("roots: blank env defaults to user.home") {
        assert(AllowedRoots.roots(Some("   ")) == List(Paths.get(home).toAbsolutePath.normalize()))
    }

    test("roots: single configured root") {
        assert(AllowedRoots.roots(Some("/data")) == List(Paths.get("/data").toAbsolutePath.normalize()))
    }

    test("roots: multiple roots split on path separator") {
        val raw = s"/data${sep}/work"
        assert(AllowedRoots.roots(Some(raw)) ==
            List("/data", "/work").map(p => Paths.get(p).toAbsolutePath.normalize()))
    }

    test("roots: settings value used when env is absent") {
        assert(AllowedRoots.roots(envValue = None, settingsValue = Some("/srv/work")) ==
            List(Paths.get("/srv/work").toAbsolutePath.normalize()))
    }

    test("roots: env value takes precedence over settings") {
        assert(AllowedRoots.roots(envValue = Some("/env/dir"), settingsValue = Some("/set/dir")) ==
            List(Paths.get("/env/dir").toAbsolutePath.normalize()))
    }

    test("roots: blank env falls through to settings") {
        assert(AllowedRoots.roots(envValue = Some("  "), settingsValue = Some("/set/dir")) ==
            List(Paths.get("/set/dir").toAbsolutePath.normalize()))
    }

    // ── isAllowed: containment ────────────────────────────────────────────────

    test("isAllowed: the allowed root itself") {
        assert(AllowedRoots.isAllowed(home))
    }

    test("isAllowed: nested path under an allowed root") {
        assert(AllowedRoots.isAllowed(Paths.get(home, "proj", "docs").toString))
    }

    test("isAllowed: rejects path outside the allowlist") {
        assert(!AllowedRoots.isAllowed(outside))
    }

    test("isAllowed: rejects filesystem root") {
        assert(!AllowedRoots.isAllowed(if (isWin) "C:\\" else "/"))
    }

    test("isAllowed: traversal that escapes the root is rejected") {
        val escaped = Paths.get(home).getParent.resolve("outside_home").toString
        assert(!AllowedRoots.isAllowed(Paths.get(home, "..", "outside_home").toString) ||
               escaped.startsWith(home)) // degenerate when home is '/'
    }

    test("isAllowed: sibling prefix that merely shares a name prefix is rejected") {
        val sibling = home + "-evil"
        assert(!AllowedRoots.isAllowed(sibling))
    }

    test("isAllowed: malformed path is rejected, not thrown") {
        assert(!AllowedRoots.isAllowed("\u0000"))
    }

    test("isAllowed: explicit roots parameter") {
        val roots = List(Paths.get("/srv/workspaces").toAbsolutePath.normalize())
        assert(AllowedRoots.isAllowed("/srv/workspaces/team", roots))
        assert(!AllowedRoots.isAllowed("/srv/other", roots))
    }
}
