package agentica.eval

import agentica.llm.OpenAIProvider
import agentica.platform.AppDirs
import ujson.{Arr, Bool, Null, Num, Obj, Str}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 *  Re-scores persisted eval answers with an LLM judge, without re-running answer
 *  generation. Two modes:
 *
 *    Repair (default): re-judges only answers whose judge call failed
 *      (`judgeStatus` = `judge-timeout`/`judge-error`) and whose agent turn
 *      completed (`status` = `answered`). Updates `questions.json` and
 *      `summary.json` in place; originals are backed up to `.bak` (kept from the
 *      first repair run only). The previous verdict is appended to a
 *      `judgeHistory` array per question before the canonical fields are
 *      overwritten.
 *
 *    Compare (`--label=<name>`): re-scores every `answered` question and writes
 *      side-by-side `questions.<label>.json` / `summary.<label>.json` next to the
 *      canonical files, which are left untouched. Use this to compare judge
 *      prompt/model/prompt-rubric variants against an existing baseline.
 *
 *  Usage:
 *    RESCORE_ARGS="latest" mvn test -Dsuites=agentica.eval.EvalRescorerSuite
 *    RESCORE_ARGS="/abs/path/doc-eval-YYYYMMDD-HHMMSS --dry-run" \
 *        mvn test -Dsuites=agentica.eval.EvalRescorerSuite
 *    RESCORE_ARGS="latest --label=gptoss-v2 --judge-model=google/gemma-4-12b" \
 *        mvn test -Dsuites=agentica.eval.EvalRescorerSuite
 *
 *  Arguments:
 *    <runDir|latest>      Run directory under `AppDirs.evalsDir`, or `latest`
 *                         (most recent `doc-eval-*`). Required, first.
 *    --dry-run            List eligible answers per sweep; do not call the judge
 *                         or write files.
 *    --label=<name>       Compare mode: write `questions.<name>.json` and
 *                         `summary.<name>.json`; canonical files untouched.
 *    --judge-url=<url>    Judge base URL override (default: judgeBaseURL from
 *                         `eval-config.json`, falling back to `llmBaseURL`).
 *    --judge-model=<id>   Judge model override (default: judgeModel from config,
 *                         falling back to `llmModel`).
 *    --judge-timeout-ms=<n>  Per-answer judge timeout (default 120000).
 *
 *  Before re-scoring, a pre-flight `GET <judge-url>/models` verifies the judge
 *  endpoint is reachable and serves the configured model; the run aborts on
 *  connection failure or model-id mismatch.
 *
 *  Re-judged entries are stamped `"rejudged": true`, `"rejudgeModel"`, and
 *  `"rejudgedAt"` so reports can distinguish them from same-run verdicts.
 */
object EvalRescorer
{
    /** Default per-answer judge call timeout. */
    private val DefaultJudgeTimeoutMs: Long = 120000L

    /**
     *  Parsed command line.
     *  @param runDir      Eval run directory to process.
     *  @param label       Compare-mode label; `None` selects repair mode.
     *  @param judgeURL    Optional judge endpoint override.
     *  @param judgeModel  Optional judge model override.
     *  @param judgeTimeoutMs  Per-answer judge call timeout.
     *  @param dryRun      When true, only report eligibility without judging or writing.
     */
    private case class Cli(
        runDir:         Path,
        label:          Option[String],
        judgeURL:       Option[String],
        judgeModel:     Option[String],
        judgeTimeoutMs: Long,
        dryRun:         Boolean
    )

    /**
     *  Prints `msg` plus usage text to stderr and throws [[IllegalArgumentException]].
     *  @param msg  Description of the invalid invocation or failed precondition.
     */
    private def usage(msg: String): Nothing =
        throw IllegalArgumentException(
            s"$msg\n\nUsage: EvalRescorer <runDir|latest> [--dry-run] [--label=<name>]\n" +
            "                    [--judge-url=<url>] [--judge-model=<id>] [--judge-timeout-ms=<n>]")

    /**
     *  Program entry point — exits with [[run]]'s return code.
     */
    def main(args: Array[String]): Unit = sys.exit(run(args.toList))

    /**
     *  Parses arguments, resolves the judge provider, runs the endpoint/model
     *  pre-flight, then processes every `questions.json` under the run directory.
     *  Callable from both [[main]] and [[EvalRescorerSuite]] without terminating
     *  the host JVM.
     *
     *  @param args  Command-line tokens (run dir first, then `--flags`).
     *  @return      0 on success.
     */
    def run(args: List[String]): Int =
    {
        val positional = args.filterNot(_.startsWith("--"))
        val flags      = args.filter(_.startsWith("--")).toList
        if (positional.isEmpty) usage("run directory (or 'latest') required")

        val runDir = positional.head match
        {
            case "latest" => latestRunDir()
            case p        => Paths.get(p)
        }
        if (!Files.isDirectory(runDir)) usage(s"not a directory: $runDir")

        val cli = Cli(
            runDir         = runDir,
            label          = flagValue(flags, "--label"),
            judgeURL       = flagValue(flags, "--judge-url"),
            judgeModel     = flagValue(flags, "--judge-model"),
            judgeTimeoutMs = flagValue(flags, "--judge-timeout-ms").map(_.toLong).getOrElse(DefaultJudgeTimeoutMs),
            dryRun         = flags.contains("--dry-run")
        )

        // Judge endpoint/model: CLI overrides > config judge fields > primary LLM fields.
        val configs = EvalConfig.resolve()
            .getOrElse(usage("no eval config found (DOC_EVAL_CONFIG / eval-config.json)"))
        val cfg       = configs.head
        val judgeURL  = cli.judgeURL.orElse(cfg.judgeBaseURL).getOrElse(cfg.llmBaseURL)
        val judgeModel = cli.judgeModel.orElse(cfg.judgeModel).getOrElse(cfg.llmModel)

        val questionFiles = Files.walk(runDir).iterator().asScala
            .filter(_.getFileName.toString == "questions.json").toList.sorted
        if (questionFiles.isEmpty) { println(s"No questions.json files under $runDir"); return 1 }

        println(s"Run dir:  $runDir")
        println(s"Judge:    $judgeURL  model=$judgeModel")
        println(s"Mode:     ${if (cli.dryRun) "dry-run" else cli.label.map(l => s"compare ($l)").getOrElse("repair")}\n")
        reportEligibility(questionFiles, cli)
        if (cli.dryRun) { println("\nDone."); return 0 }

        preflight(judgeURL, judgeModel)
        val provider = OpenAIProvider(
            baseURL               = judgeURL,
            modelName             = judgeModel,
            apiKey                = cfg.judgeAPIKey.getOrElse(cfg.llmAPIKey),
            requestTimeoutSeconds = (cli.judgeTimeoutMs / 1000).toInt.max(1),
            temperature           = cfg.temperature
        )

        for qf <- questionFiles do rescoreSweep(qf, provider, judgeModel, cli)

        println("\nDone.")
        0
    }

    /** Extracts the value of a `--key=value` flag. */
    private def flagValue(flags: List[String], key: String): Option[String] =
        flags.collectFirst { case f if f.startsWith(s"$key=") => f.drop(key.length + 1) }

    /** Returns the most recently modified `doc-eval-*` directory under [[AppDirs.evalsDir]]. */
    private def latestRunDir(): Path =
        Files.list(AppDirs.evalsDir).iterator().asScala
            .filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("doc-eval-"))
            .toList
            .sortBy(p => Files.getLastModifiedTime(p).toMillis)
            .lastOption
            .getOrElse(usage(s"no doc-eval-* directories under ${AppDirs.evalsDir}"))

    /**
     *  Pre-flight check: the judge endpoint must respond and list the configured
     *  model id. Aborts on connection failure or model mismatch — in both cases
     *  nothing would be judged anyway.
     */
    private def preflight(baseURL: String, model: String): Unit =
    {
        val url = baseURL.stripSuffix("/") match
        {
            case b if b.endsWith("/v1") => s"$b/models"
            case b                      => s"$b/v1/models"
        }
        try
        {
            val resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200)
                usage(s"judge endpoint $url returned HTTP ${resp.statusCode()}")
            val ids = ujson.read(resp.body()).obj.get("data")
                .map(_.arr.map(_("id").str).toList).getOrElse(Nil)
            if (!ids.contains(model))
                usage(s"judge model '$model' not served by $url — available: ${ids.mkString(", ")}")
            println(s"Pre-flight OK: $model served by $baseURL")
        }
        catch { case t: Throwable => usage(s"judge endpoint unreachable: $url (${t.getMessage})") }
    }

    /**
     *  Prints how many answers per sweep would be re-judged under the current mode.
     *
     *  @param questionFiles  All `questions.json` files under the run directory.
     *  @param cli            Parsed options (mode depends on `label`).
     */
    private def reportEligibility(questionFiles: List[Path], cli: Cli): Unit =
    {
        for qf <- questionFiles do
        {
            val entries  = ujson.read(Files.readString(qf)).arr.toList
            val eligible = entries.count(e =>
                e("status").str == "answered" &&
                    (cli.label.isDefined ||
                        Set("judge-timeout", "judge-error").contains(e("judgeStatus").str)))
            println(s"${runDirRelative(cli.runDir, qf.getParent)}: $eligible/${entries.size} answers to re-judge")
        }
    }

    /**
     *  Re-scores one sweep's `questions.json`.
     *
     *  In repair mode, only answered questions with failed judge calls are
     *  re-scored and the canonical files are updated in place (`.bak` preserved).
     *  In compare mode, every answered question is re-scored into
     *  `questions.<label>.json` / `summary.<label>.json`.
     *
     *  @param questionsFile  Path to the sweep's `questions.json`.
     *  @param provider       Judge LLM provider.
     *  @param judgeModel     Judge model id, stamped on re-scored entries.
     *  @param cli            Parsed options.
     */
    private def rescoreSweep(questionsFile: Path, provider: OpenAIProvider,
                             judgeModel: String, cli: Cli): Unit =
    {
        val dir      = questionsFile.getParent
        val sweep    = runDirRelative(cli.runDir, dir)
        val entries  = ujson.read(Files.readString(questionsFile)).arr.toList

        val eligible = entries.filter { e =>
            e("status").str == "answered" &&
                (cli.label.isDefined ||
                    Set("judge-timeout", "judge-error").contains(e("judgeStatus").str))
        }
        println(s"$sweep: ${eligible.size}/${entries.size} answers to re-judge")
        if (eligible.isEmpty || cli.dryRun) return

        val now  = Instant.now().toString
        val out  = Arr()
        var done = 0
        entries.foreach { e =>
            val o = Obj.from(e.obj)
            val isEligible = eligible.contains(e)
            if (isEligible)
            {
                val (verdict, rationale, status, attempts) = EvalHarness.judgeAnswer(
                    o("question").str, o("referenceAnswer").str, o("actualAnswer").str,
                    provider, cli.judgeTimeoutMs)
                done += 1
                println(s"  [$done/${eligible.size}] ${status.label} ${verdict.map(_.label).getOrElse("-")} — ${o("question").str.take(70)}")

                // Preserve the previous canonical judgement in history before overwriting.
                val history = o.obj.get("judgeHistory")
                    .map(_.arr)
                    .getOrElse(scala.collection.mutable.ArrayBuffer.empty[ujson.Value])
                history += Obj(
                    "timestamp"      -> Str(now),
                    "judgeModel"     -> Str(o.obj.get("rejudgeModel").map(_.str).getOrElse("original")),
                    "verdict"        -> o.obj.getOrElse("verdict", Null),
                    "judgeScore"     -> o.obj.getOrElse("judgeScore", Num(0)),
                    "judgeRationale" -> Str(o("judgeRationale").str),
                    "judgeStatus"    -> Str(o("judgeStatus").str)
                )
                o("judgeHistory")   = Arr.from(history)
                o("verdict")        = verdict.map(v => Str(v.label)).getOrElse(Null)
                o("judgeScore")     = Num(verdict.map(JudgeVerdict.score).getOrElse(0.0))
                o("judgeRationale") = Str(rationale)
                o("judgeStatus")    = Str(status.label)
                o("judgeAttempts")  = Num(attempts)
                o("rejudged")       = Bool(true)
                o("rejudgeModel")   = Str(judgeModel)
                o("rejudgedAt")     = Str(now)
            }
            out.value += o
        }

        val (qOut, sOut) = cli.label match
        {
            case Some(l) => (dir.resolve(s"questions.$l.json"), dir.resolve(s"summary.$l.json"))
            case None    =>
                backupOnce(questionsFile)
                val sp = dir.resolve("summary.json"); if (Files.exists(sp)) backupOnce(sp)
                (questionsFile, sp)
        }
        Files.writeString(qOut, out.render(indent = 2))
        updateSummary(sOut, dir.resolve("summary.json"), out.value.toList, judgeModel, cli.label, now)

        // Report before/after for repair mode.
        if (cli.label.isEmpty)
        {
            val judged = out.value.toList.filter(_("judgeStatus").str == "judged")
            val avg = if (judged.isEmpty) 0.0 else judged.map(_("judgeScore").num).sum / judged.length
            println(f"  -> $sweep averageJudgeScore = $avg%.3f (${judged.size}/${out.value.size} judged)")
        }
    }

    /** Copies `p` to `p.bak` only when no `.bak` exists — preserves the true original. */
    private def backupOnce(p: Path): Unit =
    {
        val bak = p.resolveSibling(p.getFileName.toString + ".bak")
        if (!Files.exists(bak)) Files.copy(p, bak, StandardCopyOption.COPY_ATTRIBUTES)
    }

    /**
     *  Writes `summary.json` (repair) or `summary.<label>.json` (compare) with
     *  judge aggregates recomputed from the updated questions. Non-judge fields
     *  (markdown score, tool counts, answered/timeout counts) are copied from the
     *  existing canonical summary.
     *
     *  @param outPath        Summary file to write.
     *  @param canonicalPath  Canonical `summary.json` to inherit base fields from.
     *  @param answers        Updated question objects.
     *  @param judgeModel     Judge model used for this pass.
     *  @param label          Compare-mode label; `None` for repair mode.
     *  @param timestamp      Re-judge timestamp.
     */
    private def updateSummary(outPath: Path, canonicalPath: Path, answers: List[ujson.Value],
                              judgeModel: String, label: Option[String], timestamp: String): Unit =
    {
        val judged          = answers.filter(_("judgeStatus").str == "judged")
        val avgScore        = if (judged.isEmpty) 0.0 else judged.map(_("judgeScore").num).sum / judged.length
        val verdictStr      = (e: ujson.Value) => e.obj.get("verdict").flatMap { case Str(s) => Some(s); case _ => None }

        val base = if (Files.exists(canonicalPath)) Obj.from(ujson.read(Files.readString(canonicalPath)).obj)
                   else Obj()
        base("averageJudgeScore") = Num(avgScore)
        base("judgedCount")       = Num(judged.length)
        base("verdicts")          = Obj(
            "correct" -> Num(judged.count(verdictStr(_).contains("correct"))),
            "partial" -> Num(judged.count(verdictStr(_).contains("partial"))),
            "wrong"   -> Num(judged.count(verdictStr(_).contains("wrong")))
        )
        base("judgeTimeoutCount") = Num(answers.count(_("judgeStatus").str == "judge-timeout"))
        base("judgeErrorCount")   = Num(answers.count(_("judgeStatus").str == "judge-error"))
        base("judgeRetryCount")   = Num(answers.count(_("judgeAttempts").num > 1))
        base("rejudged")          = Bool(true)
        base("rejudgeModel")      = Str(judgeModel)
        base("rejudgedAt")        = Str(timestamp)
        label.foreach(l => base("judgeLabel") = Str(l))
        Files.writeString(outPath, base.render(indent = 2))
    }

    /** Pretty-prints `dir` relative to the run directory for logging. */
    private def runDirRelative(runDir: Path, dir: Path): String =
        runDir.relativize(dir).toString match
        {
            case "" => dir.getFileName.toString
            case r  => r
        }
}

/**
 *  Maven entry point for [[EvalRescorer]]. Driven by the `RESCORE_ARGS`
 *  environment variable so the arguments reach the forked test JVM.
 *
 *  Usage:
 *    RESCORE_ARGS="latest" mvn test -Dsuites=agentica.eval.EvalRescorerSuite
 *    RESCORE_ARGS="latest --dry-run" mvn test -Dsuites=agentica.eval.EvalRescorerSuite
 *    RESCORE_ARGS="latest --label=gptoss-v2 --judge-model=google/gemma-4-12b" \
 *        mvn test -Dsuites=agentica.eval.EvalRescorerSuite
 *
 *  See [[EvalRescorer]] for the full argument reference.
 */
class EvalRescorerSuite extends org.scalatest.funsuite.AnyFunSuite
{
    test("rescore persisted eval answers")
    {
        sys.env.get("RESCORE_ARGS").map(_.trim).filter(_.nonEmpty) match
        {
            case Some(line) =>
                assert(EvalRescorer.run(line.split("\\s+").toList) == 0)
            case None =>
                info("skipped — set RESCORE_ARGS=\"<runDir|latest> [--label=..] [--dry-run]\" to run")
        }
    }
}
