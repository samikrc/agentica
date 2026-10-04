//> using scala 3.7.4
//> using dep com.lihaoyi::upickle:4.4.3

/** Aggregates evaluation run metadata and summary statistics from persisted
  * Agentica eval output folders.
  *
  * Usage:
  *   scala-cli backend/src/test/scala/agentica/eval/eval-report.sc
  *   scala-cli backend/src/test/scala/agentica/eval/eval-report.sc /path/to/evals
  *
  * The script scans `<evals-root>/pdf-eval-*` directories.  Each run folder is
  * expected to contain one or more PDF-stem subdirectories, each with provider
  * directories that hold `manifest.json` and per-session `summary.json` files.
  */

import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors
import scala.jdk.CollectionConverters.*

val Home = sys.props("user.home")

val EvalsRoot: Path =
    val arg = args.lift(0)
    arg match
    {
        case Some(path) => Paths.get(path).toAbsolutePath.normalize()
        case None =>
            val state = sys.env.getOrElse("AGENTICA_STATE_DIR", Paths.get(Home, ".local", "state", "Agentica").toString)
            Paths.get(state).resolve("evals").toAbsolutePath.normalize()
    }

private def hostOf(url: String): String =
    url.replaceAll("^https?://", "").replaceAll("/v1/?$", "").takeWhile(_ != '/')

private def fmtModel(model: Option[String], baseURL: Option[String]): String =
    (model, baseURL) match
    {
        case (Some(m), Some(u)) => s"$m @ ${hostOf(u)}"
        case (Some(m), None)    => m
        case (None, Some(u))    => s"? @ ${hostOf(u)}"
        case (None, None)       => "?"
    }

private def readJson(path: Path): Option[ujson.Value] =
    if Files.exists(path) then
        try Some(ujson.read(Files.readString(path)))
        catch case _: Exception => None
    else None

private def objMap(v: ujson.Value): collection.mutable.Map[String, ujson.Value] =
    v match
    {
        case ujson.Obj(m) => m
        case _            => collection.mutable.Map.empty
    }

private def objNum(obj: ujson.Value, key: String): Double =
    objMap(obj).get(key).map(_.num).getOrElse(0.0)

private def strOpt(obj: ujson.Value, key: String): Option[String] =
    objMap(obj).get(key).flatMap {
        case ujson.Str(s) => Some(s)
        case _            => None
    }

private def fmtSummary(summary: ujson.Value, manifest: Option[ujson.Value]): String =
    val label     = strOpt(summary, "providerLabel").getOrElse("?")
    val pdfFile   = strOpt(summary, "pdfFile").getOrElse("?")
    val avg       = objNum(summary, "averageJudgeScore")
    val answered  = objNum(summary, "answeredCount").toInt
    val timeouts  = objNum(summary, "timeoutCount").toInt
    val errors    = objNum(summary, "errorCount").toInt
    val jTimeouts = objNum(summary, "judgeTimeoutCount").toInt
    val jErrors   = objNum(summary, "judgeErrorCount").toInt
    val retried   = objNum(summary, "retriedCount").toInt
    val verdicts  = objMap(summary).getOrElse("verdicts", ujson.Obj())
    val correct   = objNum(verdicts, "correct").toInt
    val partial   = objNum(verdicts, "partial").toInt
    val wrong     = objNum(verdicts, "wrong").toInt

    val llm   = fmtModel(manifest.flatMap(m => strOpt(m, "llmModel")),
                        manifest.flatMap(m => strOpt(m, "llmBaseURL")))
    val vlm   = fmtModel(manifest.flatMap(m => strOpt(m, "vlmModel")),
                        manifest.flatMap(m => strOpt(m, "vlmBaseURL")))
    val judge = fmtModel(manifest.flatMap(m => strOpt(m, "judgeModel")),
                        manifest.flatMap(m => strOpt(m, "judgeBaseURL")))

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
        Option.when(timeouts > 0)(s"$timeouts timeout"),
        Option.when(errors > 0)(s"$errors error"),
        Option.when(jTimeouts > 0)(s"$jTimeouts judge-timeout"),
        Option.when(jErrors > 0)(s"$jErrors judge-error")
    ).flatten

    val flagStr = if flags.isEmpty then "" else flags.mkString("; ", "; ", "")

    f"""  $pdfFile / $label
      |    LLM: $llm
      |    VLM: $vlm | judge: $judge
      |    avg=${avg}%.2f (${answered + timeouts + errors} questions$flagStr)
      |    correct:$correct partial:$partial wrong:$wrong
      |    md: $md | tools: $tools""".stripMargin

if !Files.exists(EvalsRoot) then
{
    println(s"Eval directory not found: $EvalsRoot")
    sys.exit(1)
}

private def listDirs(path: Path): List[Path] =
    scala.util.Using.resource(Files.list(path)) { stream =>
        stream.filter(p => Files.isDirectory(p)).iterator().asScala.toList
    }

private def walkFiles(path: Path): List[Path] =
    scala.util.Using.resource(Files.walk(path)) { stream =>
        stream.filter(p => Files.isRegularFile(p)).iterator().asScala.toList
    }

val runDirs = listDirs(EvalsRoot)
    .filter(_.getFileName.toString.startsWith("pdf-eval-"))
    .sortBy(_.toString)
    .reverse

if runDirs.isEmpty then
{
    println(s"No pdf-eval-* directories found under $EvalsRoot")
    sys.exit(0)
}

var totalRuns   = 0
var totalSweeps = 0

runDirs.foreach { runDir =>
    totalRuns += 1
    println(runDir.getFileName.toString)

    val summaries = walkFiles(runDir)
        .filter(p => p.getFileName.toString == "summary.json")
        .sortBy(_.toString)

    if summaries.isEmpty then
    {
        println("  (no summary.json files found)")
    }
    else summaries.foreach { summaryPath =>
        totalSweeps += 1
        val summary  = readJson(summaryPath).getOrElse(ujson.Obj())
        // summary.json lives at .../provider/session/summary.json
        val providerDir = summaryPath.getParent.getParent
        val manifest  = readJson(providerDir.resolve("manifest.json"))
        println(fmtSummary(summary, manifest))
    }

    println()
}

println(s"Scanned $totalRuns run(s), $totalSweeps sweep(s).")
