package agentica.agent

import agentica.testutil.GoldenScenarioRunner
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Path, Paths}

/**
 *  Golden scenario integration tests.
 *  Each test runs a full agent loop with a scripted LLM provider,
 *  asserting tool call sequences and final answers match expectations.
 */
class GoldenScenarioTest extends AnyFunSuite
{

    private val scenariosDir: Path =
        Paths.get(getClass.getResource("/scenarios").toURI)

    private def scenarioPath(name: String): Path =
        scenariosDir.resolve(s"$name.json")

    // ── Scenario 1: Simple file read ───────────────────────────────────────────

    test("golden scenario: read_file") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("read_file"),
            workspaceFiles = Map("data.txt" -> "Hello, this is test data!")
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.read path=data.txt")
                .assertFinalAnswerContains("file contains")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 2: List and search ────────────────────────────────────────────

    test("golden scenario: list_and_search") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("list_and_search"),
            workspaceFiles = Map(
                "src/main.py"     -> "# Main module\n# TODO: implement main",
                "src/utils.py"    -> "# Utils\n# TODO: refactor",
                "src/README.md"   -> "# Source code"
            )
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.list path=src recursive=true", "files.search query=TODO path=src")
                .assertFinalAnswerContains("TODO")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 3: Multi-tool single response ─────────────────────────────────

    test("golden scenario: multi_tool_single_response") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("multi_tool_single_response"),
            workspaceFiles = Map(
                "README.md"       -> "# Project README",
                "src/main.py"     -> "print('hello world')"
            )
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.stat path=README.md", "files.read path=src/main.py")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 4: Use memory ────────────────────────────────────────────────

    test("golden scenario: use_memory") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("use_memory"),
            workspaceFiles = Map.empty
        )

        try
        {
            runner
                .run()
                .assertToolSequence("memory.set key=preference value=dark_mode", "memory.get key=preference")
                .assertFinalAnswerContains("Stored")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 5: Error recovery (file not found, then search) ────────────────

    test("golden scenario: search_with_error_recovery") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("search_with_error_recovery"),
            workspaceFiles = Map(
                "report_2023.txt" -> "Annual report 2023",
                "report_2024.txt" -> "Annual report 2024"
            )
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.read path=report.txt", "files.search query=report include=*.txt")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 6: Two iterations (stat then read) ───────────────────────────

    test("golden scenario: iteration_boundary") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("iteration_boundary"),
            workspaceFiles = Map("data.json" -> "{\"key\": \"value\"}")
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.stat path=data.json", "files.read path=data.json")
                .assertFinalAnswerContains("JSON")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 7: Deep recursive listing ───────────────────────────────────

    test("golden scenario: deep_list") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("deep_list"),
            workspaceFiles = Map(
                "src/main.py"           -> "main",
                "src/lib/helpers.py"    -> "helpers",
                "tests/test_main.py"    -> "test",
                "docs/README.md"        -> "docs"
            )
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.list path=. recursive=true depth=2")
        }
        finally
        {
            runner.cleanup()
        }
    }

    // ── Scenario 8: Stat then read with line limit ─────────────────────────────

    test("golden scenario: stat_and_summarize") {
        val runner = new GoldenScenarioRunner(
            scenarioPath = scenarioPath("stat_and_summarize"),
            workspaceFiles = Map("large.log" -> (1 to 200).map(i => s"Log line $i").mkString("\n"))
        )

        try
        {
            runner
                .run()
                .assertToolSequence("files.stat path=large.log", "files.read path=large.log lines=1-100")
        }
        finally
        {
            runner.cleanup()
        }
    }
}
