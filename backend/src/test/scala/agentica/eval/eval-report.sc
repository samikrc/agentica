//> using scala 3.7.4
//> using dep com.lihaoyi::upickle:4.4.3

/** Aggregates evaluation run metadata and summary statistics from persisted
 *  Agentica eval output folders.
 *
 *  Usage (run from the project root):
 *    scala backend/src/test/scala/agentica/eval/eval-report.sc
 *    scala backend/src/test/scala/agentica/eval/eval-report.sc /path/to/evals
 *
 *  Or, if your working directory is `backend/`:
 *    scala src/test/scala/agentica/eval/eval-report.sc
 *
 *  The script scans `<evals-root>/pdf-eval-*` directories.  Each run folder is
 *  expected to contain one or more PDF-stem subdirectories, each with provider
 *  directories that hold `manifest.json` and per-session `summary.json` files.
 */

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Home directory of the current user. */
val Home = sys.props("user.home")

/** Root directory used to locate `pdf-eval-*` run folders.
 *
 *  Resolves to the first command-line argument if supplied, otherwise falls
 *  back to `$AGENTICA_STATE_DIR/evals` or `~/.local/state/Agentica/evals`.
 */
val EvalsRoot: Path =
{
    val arg = args.lift(0)
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
    val pdfFile   = strOpt(summary, "pdfFile").getOrElse("?")
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

    Some(f"""  $pdfFile / $label / $sessionType
      |    LLM: $llm
      |    VLM: $vlm
      |    judge: $judge
      |    avg=${avg}%.2f (${answered + timeouts + errors} questions$flagStr)
      |    correct:$correct partial:$partial wrong:$wrong
      |    md: $md
      |    tools: $tools""".stripMargin)
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

/** Sort key that orders `pdf-eval-YYYYMMDDhhmmss[-N]` runs chronologically.
 *
 *  @param name Run directory name.
 *  @return     A tuple of timestamp string and optional disambiguation suffix.
 */
private def runSortKey(name: String): (String, Int) =
    """pdf-eval-(\d{14})(?:-(\d+))?""".r.findFirstMatchIn(name) match
    {
        case Some(m) =>
            (m.group(1), Option(m.group(2)).map(_.toInt).getOrElse(0))
        case None    => (name, 0)
    }

// Main reporting logic.
if !Files.exists(EvalsRoot) then
{
    println(s"Eval directory not found: $EvalsRoot")
    sys.exit(1)
}

val runDirs = listDirs(EvalsRoot)
    .filter(_.getFileName.toString.startsWith("pdf-eval-"))
    .sortBy(p => runSortKey(p.getFileName.toString))

if runDirs.isEmpty then
{
    println(s"No pdf-eval-* directories found under $EvalsRoot")
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
        fmtSummary(summary, manifest, sessionType)
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
