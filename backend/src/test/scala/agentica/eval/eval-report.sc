//> using scala 3.7.4
//> using dep com.lihaoyi::upickle:4.4.3

/** Aggregates evaluation run metadata and summary statistics from persisted
 *  Agentica eval output folders.
 *
 *  Usage (run from the project root; note the `--` separator before arguments):
 *    scala backend/src/test/scala/agentica/eval/eval-report.sc
 *    scala backend/src/test/scala/agentica/eval/eval-report.sc -- /path/to/evals
 *    scala backend/src/test/scala/agentica/eval/eval-report.sc -- <run-dir-A> <run-dir-B>   # side-by-side comparison
 *
 *  Or, if your working directory is `backend/`:
 *    scala src/test/scala/agentica/eval/eval-report.sc
 *
 *  The script scans `<evals-root>/doc-eval-*` directories.  Each run folder is
 *  expected to contain one or more document-stem subdirectories, each with provider
 *  directories that hold `manifest.json` and per-session `summary.json` files.
 */

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Home directory of the current user. */
val Home = sys.props("user.home")

/** Root directory used to locate `doc-eval-*` (and legacy `pdf-eval-*`) run folders.
 *
 *  Resolves to the first command-line argument if supplied, otherwise falls
 *  back to `$AGENTICA_STATE_DIR/evals` or `~/.local/state/Agentica/evals`.
 */
/** Whether the invocation is comparison mode: two existing directories. */
val CompareMode: Boolean =
    args.length == 2 && Files.isDirectory(Paths.get(args(0))) && Files.isDirectory(Paths.get(args(1)))

val EvalsRoot: Path =
{
    val arg = args.lift(0).filterNot(_ => CompareMode)
    arg match
    {
        case Some(path) => Paths.get(path).toAbsolutePath.normalize()
        case None       =>
            val state = sys.env.getOrElse(
                "AGENTICA_STATE_DIR",
                Paths.get(Home, ".local", "state", "Agentica").toString
            )
            Paths.get(state).resolve("evals").toAbsolutePath.normalize()
    }
}

/** Extracts a concise host identifier from an endpoint URL.
 *
 *  @param url Endpoint URL, possibly containing a scheme or `/v1` suffix.
 *  @return    The host portion of the URL (e.g. `api.example.com`).
 */
private def hostOf(url: String): String =
{
    url.replaceAll("^https?://", "")
       .replaceAll("/v1/?$", "")
       .takeWhile(_ != '/')
}

/** Renders a model/base-URL pair for reports.
 *
 *  @param model   Optional model identifier.
 *  @param baseURL Optional endpoint URL.
 *  @return        Human-readable model label.
 */
private def fmtModel(model: Option[String], baseURL: Option[String]): String =
    (model, baseURL) match
    {
        case (Some(m), Some(u)) => s"$m @ ${hostOf(u)}"
        case (Some(m), None)    => m
        case (None,    Some(u)) => s"? @ ${hostOf(u)}"
        case (None,    None)    => "?"
    }

/** Reads a JSON file if it exists, returning `None` on any error.
 *
 *  @param path Path to the JSON file.
 *  @return     Parsed JSON value, or `None` if the file is missing or invalid.
 */
private def readJson(path: Path): Option[ujson.Value] =
{
    if Files.exists(path) then
        try Some(ujson.read(Files.readString(path)))
        catch case _: Exception => None
    else None
}

/** Casts a [[ujson.Value]] to an object map, returning an empty map otherwise.
 *
 *  @param v A JSON value of unknown shape.
 *  @return  The underlying object map, or an empty map if `v` is not an object.
 */
private def objMap(v: ujson.Value): collection.mutable.Map[String, ujson.Value] =
    v match
    {
        case ujson.Obj(m) => m
        case _            => collection.mutable.Map.empty
    }

/** Reads a numeric field from a JSON object, defaulting to `0.0`.
 *
 *  @param obj JSON object to read from.
 *  @param key Field name.
 *  @return    Numeric value of the field, or `0.0` when missing or not a number.
 */
private def objNum(obj: ujson.Value, key: String): Double =
{
    objMap(obj).get(key).map(_.num).getOrElse(0.0)
}

/** Reads an optional string field from a JSON object.
 *
 *  @param obj JSON object to read from.
 *  @param key Field name.
 *  @return    `Some` string value, or `None` when missing or not a string.
 */
private def strOpt(obj: ujson.Value, key: String): Option[String] =
{
    objMap(obj).get(key).flatMap {
        case ujson.Str(s) => Some(s)
        case _            => None
    }
}

/** Formats a single evaluation sweep summary for console output.
 *
 *  @param summary     Per-session summary JSON.
 *  @param manifest    Optional provider manifest JSON.
 *  @param sessionType Session type label.
 *  @return            `Some` formatted lines for real providers, `None` for
 *                     synthetic entries that lack a real LLM/VLM config.
 */
private def fmtSummary(
    summary:     ujson.Value,
    manifest:    Option[ujson.Value],
    sessionType: String
): Option[String] =
{
    val label     = strOpt(summary, "providerLabel").getOrElse("?")
    val docFile   = strOpt(summary, "docFile").orElse(strOpt(summary, "pdfFile")).getOrElse("?")
    val avg       = objNum(summary, "averageJudgeScore")
    val answered  = objNum(summary, "answeredCount").toInt
    val timeouts  = objNum(summary, "timeoutCount").toInt
    val errors    = objNum(summary, "errorCount").toInt
    val jTimeouts = objNum(summary, "judgeTimeoutCount").toInt
    val jErrors   = objNum(summary, "judgeErrorCount").toInt
    val retried   = objNum(summary, "retryCount").toInt
    val jRetried  = objNum(summary, "judgeRetryCount").toInt
    val verdicts  = objMap(summary).getOrElse("verdicts", ujson.Obj())
    val correct   = objNum(verdicts, "correct").toInt
    val partial   = objNum(verdicts, "partial").toInt
    val wrong     = objNum(verdicts, "wrong").toInt

    val llm = fmtModel(
        manifest.flatMap(m => strOpt(m, "llmModel")),
        manifest.flatMap(m => strOpt(m, "llmBaseURL"))
    )
    val vlm = fmtModel(
        manifest.flatMap(m => strOpt(m, "vlmModel")),
        manifest.flatMap(m => strOpt(m, "vlmBaseURL"))
    )

    // Skip synthetic/smoke entries that carry no real provider configuration.
    if (llm == "?" || vlm == "?") then return None

    val judge = fmtModel(
        manifest.flatMap(m => strOpt(m, "judgeModel")),
        manifest.flatMap(m => strOpt(m, "judgeBaseURL"))
    )

    val md = objMap(summary).get("markdownScore") match
    {
        case Some(m) =>
            val overlap = objNum(m, "tokenOverlap")
            val ratio   = objNum(m, "wordCountRatio")
            f"overlap=${overlap}%.2f ratio=${ratio}%.2f"
        case None => "n/a"
    }

    val tools = objMap(summary).get("totalToolCounts") match
    {
        case Some(ujson.Obj(m)) if m.nonEmpty =>
            m.toList.sortBy(_._1).map { case (k, v) => s"$k=${v.num.toInt}" }.mkString(" ")
        case _ => "none"
    }

    val flags = List(
        Option.when(retried > 0)(s"$retried retried"),
        Option.when(jRetried > 0)(s"$jRetried judge-retried"),
        Option.when(timeouts > 0)(s"$timeouts timeout"),
        Option.when(errors > 0)(s"$errors error"),
        Option.when(jTimeouts > 0)(s"$jTimeouts judge-timeout"),
        Option.when(jErrors > 0)(s"$jErrors judge-error")
    ).flatten

    val flagStr = if flags.isEmpty then "" else flags.mkString("; ", "; ", "")

    Some(f"""  $docFile / $label / $sessionType
      |    LLM: $llm
      |    VLM: $vlm
      |    judge: $judge
      |    avg=${avg}%.2f (${answered + timeouts + errors} questions$flagStr)
      |    correct:$correct partial:$partial wrong:$wrong
      |    md: $md
      |    tools: $tools""".stripMargin)
}

/** Parsed arguments of one `files_search` invocation. */
case class SearchArgs(
    query:  String,
    path:   String,
    scope:  String,
    ref:    String,
    cursor: Int
)

/** Parses the argument string of one `files_search` tool call.
 *
 *  @param call Raw tool-call string as recorded in `questions.json`.
 *  @return     Parsed arguments, or `None` when the call is not a search.
 */
private def parseSearch(call: String): Option[SearchArgs] =
{
    if !call.trim.startsWith("files_search") then return None
    val argRe = """(\w+)=((?:"(?:[^"\\]|\\.)*")|\S+)""".r
    val kv    = argRe.findAllMatchIn(call).map { m =>
        val raw = m.group(2)
        val v   = if raw.startsWith("\"") then raw.drop(1).dropRight(1).replace("\\\"", "\"") else raw
        m.group(1) -> v
    }.toMap
    kv.get("query").map { q =>
        SearchArgs(
            query  = q,
            path   = kv.getOrElse("path", ""),
            scope  = kv.getOrElse("scope", "workspace"),
            ref    = kv.getOrElse("ref", ""),
            cursor = kv.get("cursor").flatMap(_.toIntOption).getOrElse(0)
        )
    }.orElse {
        // Drill-downs by ref alone, or other query-less invocations.
        if kv.contains("ref") || kv.contains("cursor") then
            Some(SearchArgs("", kv.getOrElse("path", ""), kv.getOrElse("scope", "workspace"),
                kv.getOrElse("ref", ""), kv.get("cursor").flatMap(_.toIntOption).getOrElse(0)))
        else None
    }
}

/** Normalizes a query string for duplicate comparison. */
private def normQuery(q: String): String =
    q.toLowerCase.replaceAll("[\\s]+", " ").trim

/** Token overlap ratio (Jaccard) between two queries. */
private def tokenOverlap(a: String, b: String): Double =
{
    val ta = normQuery(a).split(" ").filter(_.length > 1).toSet
    val tb = normQuery(b).split(" ").filter(_.length > 1).toSet
    if ta.isEmpty || tb.isEmpty then 0.0
    else ta.intersect(tb).size.toDouble / ta.union(tb).size
}

/** Classified search tallies for one sweep. */
case class SearchStats(
    fresh:        Int = 0,
    continuation: Int = 0,
    duplicate:    Int = 0,
    drill:        Int = 0,
    refinement:   Int = 0
)
{
    /** Total classified searches. */
    def total: Int = fresh + continuation + duplicate + drill + refinement

    /** One-line rendering of the tallies. */
    def render: String =
        s"searches=$total fresh=$fresh continuation=$continuation duplicate=$duplicate refinement=$refinement drill=$drill"

    /** Adds another tally set pointwise. */
    def +(o: SearchStats): SearchStats = SearchStats(
        fresh + o.fresh, continuation + o.continuation,
        duplicate + o.duplicate, drill + o.drill, refinement + o.refinement
    )
}

/** Classifies every `files_search` call in one `questions.json` file.
 *
 *  A call is a `continuation` when it pages (`cursor=`), a `duplicate` when its
 *  normalized query+scope+path repeat an earlier non-paged call in the same
 *  question, a `drill` when it targets stored content (`scope=stored`/`ref=`),
 *  a `refinement` when token overlap with an earlier query in the question is
 *  at least 0.4, and otherwise `fresh`.
 *
 *  @param questionsFile Path to `questions.json`.
 *  @return              Aggregated tallies, or `None` when the file is absent.
 */
private def classifySearches(questionsFile: Path): Option[SearchStats] =
{
    readJson(questionsFile).map { root =>
        var stats = SearchStats()
        root.arr.foreach { q =>
            val calls = objMap(q).get("toolCalls").map(_.arr.toList).getOrElse(Nil)
            val searches = calls.flatMap { c =>
                strOpt(c, "call").flatMap(parseSearch)
            }
            val seen = scala.collection.mutable.Set.empty[String]
            val priorQueries = scala.collection.mutable.ListBuffer.empty[String]
            searches.foreach { s =>
                val key = s"${normQuery(s.query)}|${s.scope}|${s.path}"
                val kind =
                    if s.cursor > 0 then "continuation"
                    else if seen(key) then "duplicate"
                    else if s.scope == "stored" || s.ref.nonEmpty then "drill"
                    else if priorQueries.exists(tokenOverlap(_, s.query) >= 0.4) then "refinement"
                    else "fresh"
                stats = kind match
                {
                    case "continuation" => stats.copy(continuation = stats.continuation + 1)
                    case "duplicate"    => stats.copy(duplicate = stats.duplicate + 1)
                    case "drill"        => stats.copy(drill = stats.drill + 1)
                    case "refinement"   => stats.copy(refinement = stats.refinement + 1)
                    case _              => stats.copy(fresh = stats.fresh + 1)
                }
                if s.cursor == 0 then seen += key
                if s.query.nonEmpty then priorQueries += s.query
            }
        }
        stats
    }
}

/** Lists the immediate subdirectories of `path`.
 *
 *  @param path Parent directory to scan.
 *  @return     List of child directories.
 */
private def listDirs(path: Path): List[Path] =
{
    scala.util.Using.resource(Files.list(path)) { stream =>
        stream.filter(p => Files.isDirectory(p)).iterator().asScala.toList
    }
}

/** Recursively lists regular files under `path`.
 *
 *  @param path Root directory to walk.
 *  @return     List of regular files found under `path`.
 */
private def walkFiles(path: Path): List[Path] =
{
    scala.util.Using.resource(Files.walk(path)) { stream =>
        stream.filter(p => Files.isRegularFile(p)).iterator().asScala.toList
    }
}

/** Sort key that orders `doc-eval-YYYYMMDDhhmmss[-N]` (or legacy `pdf-eval-*`) runs chronologically.
 *
 *  @param name Run directory name.
 *  @return     A tuple of timestamp string and optional disambiguation suffix.
 */
private def runSortKey(name: String): (String, Int) =
    """(?:doc|pdf)-eval-(\d{14})(?:-(\d+))?""".r.findFirstMatchIn(name) match
    {
        case Some(m) =>
            (m.group(1), Option(m.group(2)).map(_.toInt).getOrElse(0))
        case None    => (name, 0)
    }

/** Aggregated metrics for one sweep in comparison mode. */
case class SweepMetrics(
    key:      String,   // docFile / providerLabel / sessionType
    avg:      Double,
    correct:  Int,
    partial:  Int,
    wrong:    Int,
    calls:    Int,      // total tool calls
    searches: Int,      // total files_search calls
    stats:    SearchStats
)

/** Collects the sweep metrics of one run directory for comparison mode.
 *
 *  @param runDir One `doc-eval-*` run folder.
 *  @return       Sweep metrics keyed by document/provider/session.
 */
private def collectMetrics(runDir: Path): List[SweepMetrics] =
{
    walkFiles(runDir)
        .filter(p => p.getFileName.toString == "summary.json")
        .sortBy(_.toString)
        .flatMap { summaryPath =>
            val summary  = readJson(summaryPath).getOrElse(ujson.Obj())
            val manifest = readJson(summaryPath.getParent.getParent.resolve("manifest.json"))
            val llm = fmtModel(
                manifest.flatMap(m => strOpt(m, "llmModel")),
                manifest.flatMap(m => strOpt(m, "llmBaseURL"))
            )
            val vlm = fmtModel(
                manifest.flatMap(m => strOpt(m, "vlmModel")),
                manifest.flatMap(m => strOpt(m, "vlmBaseURL"))
            )
            if llm == "?" || vlm == "?" then None
            else
            {
                val label     = strOpt(summary, "providerLabel").getOrElse("?")
                val docFile   = strOpt(summary, "docFile").orElse(strOpt(summary, "pdfFile")).getOrElse("?")
                val session   = summaryPath.getParent.getFileName.toString
                val verdicts  = objMap(summary).getOrElse("verdicts", ujson.Obj())
                val tools     = objMap(summary).getOrElse("totalToolCounts", ujson.Obj())
                val toolTotal = objMap(tools).values.map(_.num.toInt).sum
                val searches  = objMap(tools).get("files_search").map(_.num.toInt).getOrElse(0)
                val stats     = classifySearches(summaryPath.getParent.resolve("questions.json"))
                    .getOrElse(SearchStats())
                Some(SweepMetrics(
                    key      = s"$docFile / $label / $session",
                    avg      = objNum(summary, "averageJudgeScore"),
                    correct  = objNum(verdicts, "correct").toInt,
                    partial  = objNum(verdicts, "partial").toInt,
                    wrong    = objNum(verdicts, "wrong").toInt,
                    calls    = toolTotal,
                    searches = searches,
                    stats    = stats
                ))
            }
        }
}

/** Prints a side-by-side comparison of two run directories.
 *
 *  @param a First (baseline) run directory.
 *  @param b Second (candidate) run directory.
 */
private def compareRuns(a: Path, b: Path): Unit =
{
    val ma = collectMetrics(a).map(m => m.key -> m).toMap
    val mb = collectMetrics(b).map(m => m.key -> m).toMap
    val keys = (ma.keySet ++ mb.keySet).toList.sorted
    println(s"A: ${a.getFileName}")
    println(s"B: ${b.getFileName}")
    println()
    keys.foreach { k =>
        (ma.get(k), mb.get(k)) match
        {
            case (Some(x), Some(y)) =>
                val dCalls = y.calls - x.calls
                val dScore = y.avg - x.avg
                println(f"  $k")
                println(f"    score   A=${x.avg}%.2f B=${y.avg}%.2f  delta=${dScore}%+.2f")
                println(f"    verdicts A=${x.correct}/${x.partial}/${x.wrong} B=${y.correct}/${y.partial}/${y.wrong} (correct/partial/wrong)")
                println(f"    calls   A=${x.calls} B=${y.calls}  delta=$dCalls%+d   searches A=${x.searches} B=${y.searches}  delta=${y.searches - x.searches}%+d")
                println(f"    A ${x.stats.render}")
                println(f"    B ${y.stats.render}")
            case (Some(x), None) => println(s"  $k  (only in A)")
            case (None, Some(y)) => println(s"  $k  (only in B)")
            case _               =>
        }
        println()
    }
}

// Main reporting logic.

// Comparison mode: two existing run directories passed as arguments.
if CompareMode then
{
    compareRuns(Paths.get(args(0)), Paths.get(args(1)))
    sys.exit(0)
}

if !Files.exists(EvalsRoot) then
{
    println(s"Eval directory not found: $EvalsRoot")
    sys.exit(1)
}

val runDirs = listDirs(EvalsRoot)
    .filter(n => n.getFileName.toString.startsWith("doc-eval-") || n.getFileName.toString.startsWith("pdf-eval-"))
    .sortBy(p => runSortKey(p.getFileName.toString))

if runDirs.isEmpty then
{
    println(s"No doc-eval-* or pdf-eval-* directories found under $EvalsRoot")
    sys.exit(0)
}

var totalRuns   = 0
var totalSweeps = 0

runDirs.foreach { runDir =>
    val summaries = walkFiles(runDir)
        .filter(p => p.getFileName.toString == "summary.json")
        .sortBy(_.toString)

    val entries = summaries.flatMap { summaryPath =>
        val summary     = readJson(summaryPath).getOrElse(ujson.Obj())
        // summary.json lives at .../provider/session/summary.json
        val providerDir = summaryPath.getParent.getParent
        val sessionType = summaryPath.getParent.getFileName.toString
        val manifest    = readJson(providerDir.resolve("manifest.json"))
        val searches    = classifySearches(summaryPath.getParent.resolve("questions.json"))
        fmtSummary(summary, manifest, sessionType).map { text =>
            searches.map(s => text + "\n    " + s.render).getOrElse(text)
        }
    }

    if entries.nonEmpty then
    {
        totalRuns += 1
        totalSweeps += entries.length
        println(runDir.getFileName.toString)
        entries.foreach(println)
        println()
    }
    else if summaries.isEmpty then
    {
        totalRuns += 1
        println(runDir.getFileName.toString)
        println("  (no summary.json files found)")
        println()
    }
}

println(s"Scanned $totalRuns run(s), $totalSweeps sweep(s).")
