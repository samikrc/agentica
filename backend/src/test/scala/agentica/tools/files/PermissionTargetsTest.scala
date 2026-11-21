package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.permissions.{GrantDecision, PermissionCoordinator, ScopeStore}
import agentica.session.{MemoryStore, Message, Session}
import agentica.shell.SessionScratchpad
import agentica.tools.ExecutionContext
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime

/**
 *  `None`-branch matrix for [[PermissionTargets]] grant-target constructors.
 *
 *  The helpers must return `None` for every call that cannot reach the
 *  protected write (escaped path, missing file, fresh output cache, missing
 *  converter, missing vision provider) so the user is never prompted for
 *  work that cannot proceed.
 */
class PermissionTargetsTest extends AnyFunSuite
{

    /** Provider stub with a controllable `supportsVision`; chat methods unused. */
    private class VisionStub(override val supportsVision: Boolean) extends LLMProvider
    {
        val modelName: String = "vision-stub"
        def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                  tools: List[ToolSpec] = Nil): LLMResponse =
            throw UnsupportedOperationException("not used")
        override def streamResponses(input: List[Message], onToken: String => Unit,
                                     previousResponseId: Option[String],
                                     tools: List[ToolSpec]): LLMResponse =
            throw UnsupportedOperationException("not used")
    }

    /** ScopeStore stub granting everything; target computation never consults it. */
    private val allGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = true
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /** Builds an ExecutionContext rooted at `root` with the given provider as primary LLM. */
    private def ctx(root: Path, provider: LLMProvider, vlm: Option[LLMProvider] = None): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(root.toString)),
            traceId         = "perm-targets-test",
            scopeStore      = allGrants,
            scratchpad      = SessionScratchpad(),
            memoryStore     = null.asInstanceOf[MemoryStore],
            llmProvider     = provider,
            vlmProvider     = vlm,
            onEvent         = (_: AgentEvent) => (),
            permissionCoordinator = new PermissionCoordinator("perm-targets-test"),
            debugMode       = false,
            vlmParallelism  = 1
        )

    /** Runs `f` with a fresh temp workspace that is deleted recursively afterwards. */
    private def withWorkspace[A](f: Path => A): A =
    {
        val ws = Files.createTempDirectory("agentica-perm-targets-test")
        try f(ws)
        finally
            if (Files.exists(ws))
                Files.walk(ws).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p => Files.deleteIfExists(p))
    }

    test("singleFileTarget: escaped path returns None") {
        withWorkspace { ws =>
            assert(PermissionTargets.singleFileTarget("../outside.txt", ctx(ws, VisionStub(true))).isEmpty)
        }
    }

    test("singleFileTarget: in-sandbox path returns resolved grant key") {
        withWorkspace { ws =>
            val target = PermissionTargets.singleFileTarget("notes.txt", ctx(ws, VisionStub(true)))
            assert(target.isDefined)
            assert(target.get.grantKey == ws.resolve("notes.txt").normalize().toString)
            assert(target.get.displayPath.contains("notes.txt"))
        }
    }

    test("directoryTarget: missing source file returns None") {
        withWorkspace { ws =>
            val t = PermissionTargets.directoryTarget("missing.pdf", enrichImages = true, ".pdf",
                ctx(ws, VisionStub(true)))
            assert(t.isEmpty)
        }
    }

    test("directoryTarget: escaped path returns None") {
        withWorkspace { ws =>
            val t = PermissionTargets.directoryTarget("../outside.pdf", enrichImages = true, ".pdf",
                ctx(ws, VisionStub(true)))
            assert(t.isEmpty)
        }
    }

    test("directoryTarget: fresh output cache returns None") {
        withWorkspace { ws =>
            Files.writeString(ws.resolve("doc.pdf"), "fake pdf")
            Files.writeString(ws.resolve("doc.md"), "x" * 200)
            val now = System.currentTimeMillis()
            Files.setLastModifiedTime(ws.resolve("doc.pdf"), FileTime.fromMillis(now - 1000))
            Files.setLastModifiedTime(ws.resolve("doc.md"), FileTime.fromMillis(now))

            val t = PermissionTargets.directoryTarget("doc.pdf", enrichImages = true, ".pdf",
                ctx(ws, VisionStub(true)))
            assert(t.isEmpty, "a fresh .md cache means no write will happen")
        }
    }

    test("directoryTarget: enrich_images with a non-vision provider returns None") {
        withWorkspace { ws =>
            Files.writeString(ws.resolve("doc.pdf"), "fake pdf")
            val t = PermissionTargets.directoryTarget("doc.pdf", enrichImages = true, ".pdf",
                ctx(ws, VisionStub(false)))
            assert(t.isEmpty, "vision-unavailable call fails before the write")
        }
    }

    test("directoryTarget: converterReady=false returns None") {
        withWorkspace { ws =>
            Files.writeString(ws.resolve("doc.docx"), "fake docx")
            val t = PermissionTargets.directoryTarget("doc.docx", enrichImages = true, ".docx",
                ctx(ws, VisionStub(true)), converterReady = false)
            assert(t.isEmpty, "missing converter means no write will happen")
        }
    }

    test("directoryTarget: plain call returns parent-directory grant") {
        withWorkspace { ws =>
            Files.writeString(ws.resolve("doc.pdf"), "fake pdf")
            val t = PermissionTargets.directoryTarget("doc.pdf", enrichImages = true, ".pdf",
                ctx(ws, VisionStub(true)))
            assert(t.isDefined)
            assert(t.get.grantKey == ".", s"expected root '.' grant key, got ${t.get.grantKey}")
            assert(t.get.displayPath.contains("."))
        }
    }

    test("directoryTarget: nested source returns the relative parent directory") {
        withWorkspace { ws =>
            Files.createDirectories(ws.resolve("docs"))
            Files.writeString(ws.resolve("docs/doc.pdf"), "fake pdf")
            val t = PermissionTargets.directoryTarget("docs/doc.pdf", enrichImages = true, ".pdf",
                ctx(ws, VisionStub(true)))
            assert(t.isDefined)
            assert(t.get.grantKey == "docs", s"expected 'docs' grant key, got ${t.get.grantKey}")
            assert(t.get.displayPath.contains("docs"))
        }
    }
}
