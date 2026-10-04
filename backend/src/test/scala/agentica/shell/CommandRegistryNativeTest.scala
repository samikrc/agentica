package agentica.shell

import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, Tool, ToolResult, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite

class CommandRegistryNativeTest extends AnyFunSuite
{
    private object SearchTool extends Tool[Map[String, String], Map[String, String]]
    {
        val name = "files_search"
        val schema = CommandSchema(
            fullName = name,
            summary = "Search files",
            args = List(
                ArgSpec("query", "Terms to find", required = true),
                ArgSpec("path", "Path to search", required = false)
            ),
            example = "files_search query=term path=."
        )
        def validate(args: Map[String, String]): Either[ArgError, Map[String, String]] = Right(args)
        def execute(input: Map[String, String], ctx: ExecutionContext): Map[String, String] = input
        def render(output: Map[String, String], ctx: ExecutionContext): ToolResult = ToolResult(ToolStatus.Ok)
    }

    /** Builds a registry containing the native-schema test tool. */
    private def registry(): CommandRegistry =
    {
        val registry = CommandRegistry()
        registry.register(SearchTool)
        registry
    }

    test("native schemas expose one function per registered command") {
        val specs = registry().nativeToolSchemas
        val search = specs.find(_.name == "files_search").get
        assert(search.description.contains("Search files"))
        assert(search.parameters("properties")("query")("type").str == "string")
        assert(search.parameters("required").arr.map(_.str) == Seq("query"))
        assert(specs.exists(_.name == "help"))
    }

    test("native JSON arguments convert to the canonical command") {
        val command = registry().commandFromNative("files_search", "{\"query\":\"term\",\"path\":\"docs\"}")
        assert(command == Right(Command("files", "search", Map("query" -> "term", "path" -> "docs"))))
    }

    test("malformed native JSON and unknown names return errors") {
        assert(registry().commandFromNative("files_search", "not-json").isLeft)
        assert(registry().commandFromNative("files_missing", "{}").isLeft)
    }

    test("help is converted to an internal help command") {
        assert(registry().commandFromNative("help", "{\"topic\":\"files_search\"}") ==
            Right(Command("help", "index", Map("topic" -> "files_search"))))
    }
}
