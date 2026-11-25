package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{MemoryStore, Session}
import agentica.shell.{Presentation, SessionScratchpad}
import agentica.tools.{ExecutionContext, ToolBody}
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import agentica.permissions.PermissionCoordinator

/**
 *  Test suite for FilesRead and FilesSearch scratchpad functionality.
 *
 *  Verifies that large file content and search results are properly stored
 *  in the SessionScratchpad when they exceed the body budget, and that
 *  small files correctly bypass scratchpad storage.
 */
class FilesReadScratchpadTest extends AnyFunSuite
{

    private val noGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = false
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /** Creates an execution context rooted at the supplied test workspace. */
    private def mkCtx(rootPath: String, scratchpad: SessionScratchpad): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(rootPath)),
            traceId         = "scratchpad-bug-test",
            scopeStore      = noGrants,
            scratchpad      = scratchpad,
            memoryStore     = null,
            llmProvider     = null,
            vlmProvider     = None,
            onEvent         = _ => (),
            permissionCoordinator = new PermissionCoordinator("test-run"),
            debugMode       = false
        )

    /** Deletes a temporary test workspace recursively. */
    private def deleteTmpDir(dir: java.nio.file.Path): Unit =
        Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete(_))

    
    test("FilesRead stores large file content in scratchpad") {
        // Tests that FilesRead properly stores content in scratchpad when file exceeds body budget
        
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-read")
        try
        {
            val bigContent = "a" * (Presentation.BODY_BUDGET_CHARS + 1000)
            val file       = tmpDir.resolve("report.txt")
            Files.writeString(file, bigContent)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input  = FilesReadInput(Paths.get("report.txt"), None)
            val output = FilesRead.execute(input, ctx)

            // Verify file was read successfully and exceeds body budget
            assert(output.error.isEmpty, "file read must succeed")
            assert(
                output.content.length > Presentation.BODY_BUDGET_CHARS,
                "content must exceed BODY_BUDGET_CHARS to trigger scratchpad storage"
            )

            // Verify render() returns ScratchRef for oversized content
            val result = FilesRead.render(output, ctx)
            val body   = result.body
            assert(body.isDefined && body.get.isInstanceOf[ToolBody.ScratchRef],
                "render() must return a ScratchRef for oversized content")

            val ref = body.get.asInstanceOf[ToolBody.ScratchRef].ref
            assert(ref == "$scratch/report.txt")

            // Verify content was actually stored in scratchpad and is accessible
            val stored = scratchpad.get(ref)
            assert(
                stored.isDefined,
                "scratchpad must contain the stored content for the ref to resolve"
            )
            assert(stored.get.content == bigContent, "stored content must match original file content")
            assert(result.metadata.get("hint").exists(_.contains("files_search")),
                "large-file result must carry an actionable search/range hint in metadata")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesRead small files return Inline body and are stored in scratchpad") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-small")
        try
        {
            val smallContent = "hello world\n"
            val file         = tmpDir.resolve("small.txt")
            Files.writeString(file, smallContent)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input  = FilesReadInput(Paths.get("small.txt"), None)
            val output = FilesRead.execute(input, ctx)
            val result = FilesRead.render(output, ctx)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "small file must return an Inline body with content")
            assert(scratchpad.size == 1,
                "small file must be stored in the scratchpad for downstream chaining")
            assert(result.metadata.get("stored").exists(_.startsWith("$scratch/")),
                "small file result must include a stored: ref in metadata")
            assert(result.metadata("stored") == "$scratch/small.txt",
                "stored ref must be path-keyed")
            val stored = scratchpad.get("$scratch/small.txt")
            assert(stored.isDefined && stored.get.content == smallContent.stripTrailing(),
                "scratchpad content must match the file (readAllLines strips trailing newline)")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesRead range reads return Inline body without scratchpad storage") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-range-read")
        try
        {
            val content = (1 to 100).map(i => s"line $i").mkString("\n")
            Files.writeString(tmpDir.resolve("lines.txt"), content)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input  = FilesReadInput(Paths.get("lines.txt"), Some((1, 10)))
            val output = FilesRead.execute(input, ctx)
            val result = FilesRead.render(output, ctx)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "range read must return Inline body")
            assert(scratchpad.size == 0,
                "range read must not store partial content in scratchpad")
            assert(!result.metadata.contains("stored"),
                "range read must not include a stored: ref")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    /** Builds validated search input with defaults for these tests. */
    private def searchInput(query: String, path: Option[String] = None,
                            limit: Int = 8, mode: String = "hybrid"): FilesSearchInput =
        FilesSearchInput(
            query    = query,
            rawPath  = path,
            scope    = "workspace",
            ref      = None,
            limit    = limit,
            cursor   = 0,
            mode     = mode,
            useRegex = false
        )

    test("FilesSearch expands a caption match to the enclosing table") {
        val tmpDir = Files.createTempDirectory("agentica-search-table-expand")
        try
        {
            val md = """|Intro prose.
                        |
                        |Exhibit 15: Options turnover
                        |
                        || Q | Value |
                        ||---|---|
                        || Q1 | 8,286 |
                        || Q2 | 8,448 |
                        || Q3 | 6,796 |
                        || Q4 | 12,257 |
                        |
                        |Tail prose.""".stripMargin
            Files.writeString(tmpDir.resolve("doc.md"), md)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val output = FilesSearch.execute(searchInput("Exhibit 15", Some("doc.md")), ctx)

            assert(output.hits.exists(_.snippet.contains("6,796")),
                "a match next to a table must surface the table's data rows")
            assert(output.hits.exists(_.snippet.contains("Q | Value")),
                "expansion must include the table header")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch emits a table once when several matches land inside it") {
        val tmpDir = Files.createTempDirectory("agentica-search-table-dedup")
        try
        {
            val md = """|| Q | Value |
                        ||---|---|
                        || Q1 | alpha |
                        || Q2 | beta |
                        || Q3 | alpha |""".stripMargin
            Files.writeString(tmpDir.resolve("doc.md"), md)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val output = FilesSearch.execute(searchInput("alpha", Some("doc.md")), ctx)

            assert(output.hits.size == 1,
                "matches inside one table must produce a single merged hit")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch merges nearby matches into one continuous excerpt") {
        val tmpDir = Files.createTempDirectory("agentica-search-merge")
        try
        {
            val text = """|alpha line one
                          |context a
                          |context b
                          |beta line four
                          |context c
                          |context d
                          |far away
                          |far away
                          |far away
                          |far away
                          |beta again eleven""".stripMargin
            Files.writeString(tmpDir.resolve("doc.txt"), text)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val output = FilesSearch.execute(searchInput("alpha,beta", Some("doc.txt")), ctx)

            // Matches at lines 1 and 4 merge into one hit; the line-11 match stays separate.
            assert(output.hits.size == 2,
                "adjacent match windows must merge; a distant match must stay separate")
            assert(output.hits.head.snippet.contains("alpha line one"))
            assert(output.hits.head.snippet.contains("beta line four"))
            assert(output.hits.last.snippet.contains("beta again eleven"))
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch small results are inline and not persisted") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-search-small")
        try
        {
            Files.writeString(tmpDir.resolve("note.txt"), "needle here\n")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val output = FilesSearch.execute(searchInput("needle"), ctx)
            val result = FilesSearch.render(output, ctx)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "small search result must return an Inline body")
            assert(!result.metadata.contains("stored"),
                "routine pages carry no stored: ref (only oversized pages overflow)")
            assert(scratchpad.size == 0,
                "routine pages are not persisted in the scratchpad")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch renders a continuation line when more hits exist") {
        val tmpDir = Files.createTempDirectory("agentica-search-paging")
        try
        {
            for (i <- 1 to 5)
                Files.writeString(tmpDir.resolve(s"file$i.txt"), s"needle in file $i\n")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val output = FilesSearch.execute(searchInput("needle", None, limit = 2), ctx)
            val result = FilesSearch.render(output, ctx)

            assert(output.hits.size == 2, "limit must cap the page size")
            assert(output.moreCommand.exists(_.contains("cursor=2")),
                "continuation command must point at the next cursor")
            val text = result.body.get.asInstanceOf[ToolBody.Inline].text
            assert(text.contains("For more entries, use: files_search"),
                "page body must end with the exact next call")
            assert(result.metadata.get("Displaying").contains("2 of 5"),
                "metadata must report the page size against the total")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch overflows an oversized single hit to the scratchpad") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-search")
        try
        {
            // One hit whose snippet alone exceeds the body budget.
            val padding = "x" * 3000
            val content = (1 to 12).map(j => s"needle line $j $padding").mkString("\n")
            Files.writeString(tmpDir.resolve("big.txt"), content)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val output = FilesSearch.execute(searchInput("needle"), ctx)
            val result = FilesSearch.render(output, ctx)

            result.body match
            {
                case Some(ref: ToolBody.ScratchRef) =>
                    assert(scratchpad.get(ref.ref).isDefined,
                        "oversized page must be stored so the ref resolves")
                    assert(result.metadata.get("stored").contains(ref.ref))
                case other =>
                    fail(s"expected ScratchRef for an oversized page, got $other")
            }
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }
}
