package agentica.eval

import agentica.agent.{AgentEvent, AgentLoop, ContextManager}
import agentica.doc.{PDFPageRenderer, PageVisionTranscriber}
import agentica.llm.{LLMProvider, LLMResponse, OpenAIProvider}
import agentica.misctests.{MarkdownScore, MarkdownScorer}
import agentica.observability.TokenAccounting
import agentica.platform.AppDirs
import agentica.permissions.{GrantDecision, GrantTTL, PermissionCoordinator, ScopeStore}
import agentica.session.{AgentTurn, AgentTurnStore, MemoryEntry, MemoryStore, Message, MessageRole, MessageStore, RunStatus, RunStore, Session, SessionStore, ToolRun}
import agentica.settings.AppSettings
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.testutil.LMStudioClient
import agentica.tools.{AgentResponse, ExecutionContext, ToolBody}
import agentica.tools.files.{FilesList, FilesRead, FilesReadDOCXToMarkdown, FilesReadPDFToMarkdown, FilesReadPDFToMarkdownInput, FilesReadPPTXToMarkdown, FilesSearch, FilesStat, FilesWrite}
import agentica.tools.memory.{MemoryGet, MemoryList, MemorySet}
import ujson.*
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.mutable

/**
 *  Configuration for one evaluated (LLM, VLM) pair.
 *
 *  @param label        Human-readable identifier used in result tables.
 *  @param llmBaseURL   Base URL of the OpenAI-compatible primary LLM server.
 *  @param llmModel     Model identifier for the primary LLM.
 *  @param llmAPIKey    API key / bearer token for the primary LLM.
 *  @param vlmBaseURL   Optional VLM server URL; None means "use primary LLM as vision fallback".
 *  @param vlmModel     Optional VLM model identifier.
 *  @param vlmAPIKey    Optional VLM API key.
 *  @param judgeBaseURL Optional judge LLM server URL; None means "use primary LLM as judge".
 *  @param judgeModel   Optional judge model identifier.
 *  @param judgeAPIKey            Optional judge API key.
 *  @param pauseBetweenPhasesMs   Optional pause (ms) between answer generation and
 *                                judging so the caller can swap models on the same
 *                                server (e.g., LM Studio). Defaults to 30 000 ms
 *                                for local endpoints if not specified.
 *  @param sessionTypes           Session modes to run for this provider. Defaults to
 *                                `List(AllQuestionsPerSession)`.
 *  @param reuseMarkdownCache     Whether matching cached Markdown may skip PDF conversion.
 */
case class EvalProviderConfig(
    label:                String,
    llmBaseURL:           String,
    llmModel:             String,
    llmAPIKey:            String,
    vlmBaseURL:           Option[String],
    vlmModel:             Option[String],
    vlmAPIKey:            Option[String],
    judgeBaseURL:         Option[String]          = None,
    judgeModel:           Option[String]          = None,
    judgeAPIKey:          Option[String]          = None,
    pauseBetweenPhasesMs: Option[Long]            = None,
    sessionTypes:         List[EvalSessionType]   = List(EvalSessionType.AllQuestionsPerSession),
    reuseMarkdownCache:   Boolean                 = true
)

/**
 *  Non-sensitive metadata about a provider sweep, persisted alongside results
 *  so downstream reporting tools can recover which models and session types
 *  were used without re-parsing the provider configuration.
 *
 *  @param providerLabel     Display name for the provider entry.
 *  @param llmBaseURL        Base URL of the primary LLM endpoint.
 *  @param llmModel          Model identifier used for the primary LLM.
 *  @param vlmBaseURL        Optional VLM endpoint URL.
 *  @param vlmModel          Optional VLM model identifier.
 *  @param judgeBaseURL      Optional judge endpoint URL.
 *  @param judgeModel        Optional judge model identifier.
 *  @param sessionTypes      Session modes executed for this provider.
 *  @param questionTimeoutMs Timeout applied to each QA agent turn.
 *  @param pdfTimeoutMs      Timeout applied to PDF-to-Markdown conversion.
 *  @param reuseMarkdownCache Whether matching cached Markdown may skip conversion.
 *  @param markdownCache     Cache decision details; `None` when conversion never ran.
 */
case class ProviderManifest(
    providerLabel:      String,
    llmBaseURL:         String,
    llmModel:           String,
    vlmBaseURL:         Option[String],
    vlmModel:           Option[String],
    judgeBaseURL:       Option[String],
    judgeModel:         Option[String],
    sessionTypes:       List[String],
    questionTimeoutMs:  Long,
    pdfTimeoutMs:       Long,
    reuseMarkdownCache: Boolean,
    markdownCache:      Option[MarkdownCacheInfo] = None
)

/**
 *  Records how the content-addressed Markdown cache was used for one provider
 *  sweep, so reports can distinguish reused Markdown from a fresh conversion.
 *
 *  @param enabled   Whether cache reuse was permitted by configuration.
 *  @param hit       Whether an existing valid entry was reused instead of converting.
 *  @param key       Content-addressed cache key (SHA-256 of version, PDF hash, identity).
 *  @param pdfSha256 SHA-256 digest of the source PDF bytes.
 *  @param source    Absolute path of the cache entry that was read or written.
 */
case class MarkdownCacheInfo(
    enabled:   Boolean,
    hit:       Boolean,
    key:       String,
    pdfSha256: String,
    source:    String
)

/**
 *  Companion factory helpers for building sweep configs from settings or environment.
 */
object EvalProviderConfig
{
    /**
     *  Builds a single provider config from application settings.
     *  The VLM and judge fields are used only when all required fields are non-empty;
     *  otherwise the primary LLM serves as the fallback.
     *
     *  @param settings  AppSettings carrying server/model/API-key values.
     *  @return          One EvalProviderConfig labelled with the model names.
     */
    def fromSettings(settings: AppSettings): EvalProviderConfig =
    {
        val hasVLM = settings.vlmServerURL.nonEmpty && settings.vlmModel.nonEmpty
        EvalProviderConfig(
            label      = if (hasVLM) s"${settings.modelName}_${settings.vlmModel}"
                         else settings.modelName,
            llmBaseURL = settings.serverURL,
            llmModel   = settings.modelName,
            llmAPIKey  = if (settings.apiKey.nonEmpty) settings.apiKey
                         else sys.env.getOrElse("LLM_API_KEY", "lm-studio"),
            vlmBaseURL = if (hasVLM) Some(settings.vlmServerURL) else None,
            vlmModel   = if (hasVLM) Some(settings.vlmModel) else None,
            vlmAPIKey  = if (hasVLM) Some(
                if (settings.vlmAPIKey.nonEmpty) settings.vlmAPIKey
                else sys.env.getOrElse("VLM_API_KEY", "lm-studio")
            ) else None
        )
    }

    /**
     *  Creates an evaluation provider configuration for one OpenAI-compatible endpoint.
     *  The primary LLM endpoint is also used for vision unless separate VLM settings are
     *  supplied through the full [[EvalProviderConfig]] constructor.
     *
     *  @param label       Human-readable provider label used in reports and output paths.
     *  @param llmBaseURL  Base URL of the OpenAI-compatible endpoint.
     *  @param llmModel    Model identifier sent to the endpoint.
     *  @param llmAPIKey   API key used to authenticate endpoint requests.
     *  @return            Provider configuration with no separate VLM override.
     */
    def apply(
        label:      String,
        llmBaseURL: String,
        llmModel:   String,
        llmAPIKey:  String
    ): EvalProviderConfig =
        EvalProviderConfig(label, llmBaseURL, llmModel, llmAPIKey, None, None, None)
}

/**
 *  Session type for evaluation: controls whether each question is asked in
 *  its own fresh session or all questions share a single conversation.
 *
 *  @param label  JSON key and folder suffix.
 */
enum EvalSessionType(val label: String)
{
    /**
     *  All questions are asked in one session as follow-up questions,
     *  with conversation history accumulating across questions.
     */
    case AllQuestionsPerSession extends EvalSessionType("all-questions-per-session")

    /**
     *  Each question is asked in an independent session with no prior history.
     */
    case OneQuestionPerSession extends EvalSessionType("one-question-per-session")

    /**
     *  Like [[AllQuestionsPerSession]] but with deterministically shuffled question order.
     */
    case AllQuestionsPerSessionShuffled extends EvalSessionType("all-questions-per-session-shuffled")
}

object EvalSessionType
{
    /**
     *  Parses a session type string (case-insensitive).
     *
     *  @param s  Kebab-case session allocation strategy.
     *  @return   Corresponding EvalSessionType.
     */
    def fromString(s: String): EvalSessionType =
    {
        s.trim.toLowerCase match
        {
            case "all-questions-per-session" => EvalSessionType.AllQuestionsPerSession
            case "all-questions-per-session-shuffled" => EvalSessionType.AllQuestionsPerSessionShuffled
            case "one-question-per-session" => EvalSessionType.OneQuestionPerSession
            case other => throw new IllegalArgumentException(
                s"Unknown sessionType: '$other'. Must be 'all-questions-per-session', " +
                "'all-questions-per-session-shuffled', or 'one-question-per-session'.")
        }
    }
}

/**
 *  Loader for evaluation sweep configurations stored as JSON.
 *
 *  The expected format is a JSON array of provider objects, each with the same
 *  fields as [[EvalProviderConfig]] plus an optional `"sessionTypes"` array.
 */
object EvalConfig
{
    /** Default classpath resource name used by [[resolve]]. */
    private val DefaultResourceName = "/eval-config.json"

    /**
     *  Loads provider configs from a JSON file on disk.
     *
     *  @param path  Path to a JSON file containing a provider array.
     *  @return      List of [[EvalProviderConfig]] entries.
     */
    def load(path: Path): List[EvalProviderConfig] =
    {
        val json = ujson.read(Files.readString(path))
        parseProviderConfigs(json)
    }

    /**
     *  Loads provider configs from a classpath resource.
     *
     *  @param resourceName  Resource path, defaulting to `/eval-config.json`.
     *  @return              Some(configs) if the resource exists, None otherwise.
     */
    def loadFromResource(resourceName: String = DefaultResourceName): Option[List[EvalProviderConfig]] =
    {
        val stream = getClass.getResourceAsStream(resourceName)
        if stream == null then None
        else
            try
            {
                val json = ujson.read(stream)
                Some(parseProviderConfigs(json))
            }
            finally
            {
                stream.close()
            }
    }

    /**
     *  Resolves the active eval configuration.
     *
     *  Priority:
     *    1. If the `PDF_EVAL_CONFIG` Java system property is set, load that file.
     *    2. Else if the `PDF_EVAL_CONFIG` environment variable is set, load that file.
     *    3. Otherwise try the default classpath resource `/eval-config.json`.
     *
     *  @return  Some(configs) if a config source is found, None otherwise.
     */
    def resolve(): Option[List[EvalProviderConfig]] =
    {
        val pathProp = sys.props.get("PDF_EVAL_CONFIG").orElse(sys.env.get("PDF_EVAL_CONFIG"))
        pathProp match
        {
            case Some(pathStr) => Some(load(Paths.get(pathStr)))
            case None          => loadFromResource()
        }
    }

    /**
     *  Parses the provider array from the config JSON.
     *
     *  @param json  JSON array of provider objects.
     *  @return      List of [[EvalProviderConfig]] entries.
     */
    private def parseProviderConfigs(json: ujson.Value): List[EvalProviderConfig] =
    {
        json.arr.toList.map { c =>
            val sessionTypes = c.obj.get("sessionTypes")
                .map(_.arr.toList.map(v => EvalSessionType.fromString(v.str)))
                .getOrElse(List(EvalSessionType.AllQuestionsPerSession))
            EvalProviderConfig(
                label                = c.obj("label").str,
                llmBaseURL           = c.obj("llmBaseURL").str,
                llmModel             = c.obj("llmModel").str,
                llmAPIKey            = c.obj.get("llmAPIKey").map(_.str).getOrElse("lm-studio"),
                vlmBaseURL           = c.obj.get("vlmBaseURL").map(_.str),
                vlmModel             = c.obj.get("vlmModel").map(_.str),
                vlmAPIKey            = c.obj.get("vlmAPIKey").map(_.str),
                judgeBaseURL         = c.obj.get("judgeBaseURL").map(_.str),
                judgeModel           = c.obj.get("judgeModel").map(_.str),
                judgeAPIKey          = c.obj.get("judgeAPIKey").map(_.str),
                pauseBetweenPhasesMs = c.obj.get("pauseBetweenPhasesMs").map(_.num.toLong),
                sessionTypes         = sessionTypes,
                reuseMarkdownCache   = c.obj.get("reuseMarkdownCache").forall(_.bool)
            )
        }
    }
}

/**
 *  One question/answer pair for a document evaluation.
 *
 *  @param question         The question to ask the agent.
 *  @param referenceAnswer  The ground-truth answer from the document.
 *  @param category         "direct" or "rephrase" (used for grouping reports).
 */
case class EvalQuestion(
    question:        String,
    referenceAnswer: String,
    category:        String = "direct"
)

/**
 *  Outcome of one QA agent turn.
 */
enum AnswerStatus:
    /** Turn completed and produced a final answer. */
    case Answered
    /** Turn or an underlying HTTP request exceeded its timeout. */
    case Timeout
    /** Turn failed with a non-timeout error. */
    case Error

object AnswerStatus:
    extension (s: AnswerStatus) def label: String = s match
    {
        case AnswerStatus.Answered => "answered"
        case AnswerStatus.Timeout  => "timeout"
        case AnswerStatus.Error    => "error"
    }

/**
 *  Outcome of judging one answer.
 */
enum JudgeStatus:
    /** Judge produced a score. */
    case Judged
    /** Judge call timed out. */
    case JudgeTimeout
    /** Judge call failed with a non-timeout error. */
    case JudgeError
    /** Judge was not invoked because the answer did not complete normally. */
    case Skipped

object JudgeStatus:
    extension (s: JudgeStatus) def label: String = s match
    {
        case JudgeStatus.Judged       => "judged"
        case JudgeStatus.JudgeTimeout => "judge-timeout"
        case JudgeStatus.JudgeError   => "judge-error"
        case JudgeStatus.Skipped      => "skipped"
    }

/**
 *  Categorical verdict produced by the judge for one answer.
 */
enum JudgeVerdict:
    /** Fully correct and complete. */
    case Correct
    /** Partially correct or missing important details. */
    case PartiallyCorrect
    /** Incorrect, unsupported, or hallucinated. */
    case Wrong

object JudgeVerdict:
    extension (v: JudgeVerdict) def label: String = v match
    {
        case JudgeVerdict.Correct          => "correct"
        case JudgeVerdict.PartiallyCorrect => "partial"
        case JudgeVerdict.Wrong            => "wrong"
    }

    /** Numeric value used to compute aggregate scores. */
    def score(v: JudgeVerdict): Double = v match
    {
        case JudgeVerdict.Correct          => 1.0
        case JudgeVerdict.PartiallyCorrect => 0.5
        case JudgeVerdict.Wrong            => 0.0
    }

    /** Maps a numeric score to the nearest verdict bucket (tolerant of stray floats). */
    def fromScore(score: Double): JudgeVerdict =
        if score >= 0.75 then Correct
        else if score >= 0.25 then PartiallyCorrect
        else Wrong

    /** Normalizes a free-form verdict string ("correct", "partially correct", ...) to a verdict. */
    def parse(text: String): Option[JudgeVerdict] =
    {
        val n = text.toLowerCase.filter(_.isLetter)
        if n.contains("partial") then Some(PartiallyCorrect)
        else if n.contains("wrong") || n.contains("incorrect") then Some(Wrong)
        else if n.contains("correct") then Some(Correct)
        else None
    }

/**
 *  Outcome of a whole provider/session-type sweep.
 */
enum SweepStatus:
    /** Sweep produced answers for all questions. */
    case Ok
    /** Sweep failed because a phase exceeded its timeout (e.g. PDF conversion). */
    case FailedTimeout
    /** Sweep failed with a non-timeout error. */
    case FailedError

object SweepStatus:
    extension (s: SweepStatus) def label: String = s match
    {
        case SweepStatus.Ok            => "ok"
        case SweepStatus.FailedTimeout => "failed-timeout"
        case SweepStatus.FailedError   => "failed-error"
    }

/**
 *  Result of judging one predicted answer.
 *
 *  @param question         Original question.
 *  @param category         Question category ("direct" or "rephrase").
 *  @param referenceAnswer  Ground-truth answer from the eval JSON.
 *  @param actualAnswer     Final answer produced by the agent (last iteration only).
 *  @param thoughts         All reasoning and intermediate text from earlier iterations.
 *  @param toolCalls        Ordered list of raw tool call commands issued by the agent.
 *  @param judgeScore       Numeric score from 0.0 to 1.0; meaningless unless [[judgeStatus]] is [[JudgeStatus.Judged]].
 *  @param judgeRationale   Short rationale produced by the judge LLM.
 *  @param toolCounts       Map of tool name -> number of calls while answering this question.
 *  @param status           Outcome of the QA agent turn.
 *  @param judgeStatus      Outcome of the judging step for this answer.
 *  @param judgeVerdict     Categorical verdict when [[judgeStatus]] is [[JudgeStatus.Judged]].
 *  @param attempts         Number of QA attempts made for this question (1 or 2 after a timeout retry).
 */
case class AnswerResult(
    question:         String,
    category:         String,
    referenceAnswer:  String,
    actualAnswer:     String,
    thoughts:         String,
    toolCalls:        List[String],
    judgeScore:       Double,
    judgeRationale:   String,
    toolCounts:       Map[String, Int],
    status:           AnswerStatus,
    judgeStatus:      JudgeStatus,
    judgeVerdict:     Option[JudgeVerdict],
    attempts:         Int
)

/**
 *  Aggregated result for one provider run against one document.
 *
 *  @param providerLabel  Label of the evaluated provider config.
 *  @param sessionType    Session allocation strategy label.
 *  @param markdownPath   Path to the generated Markdown file.
 *  @param markdownScore  MarkdownScorer output for the generated Markdown.
 *  @param answers        List of judged question/answer results.
 *  @param status         Outcome of the sweep; [[SweepStatus.Ok]] when answers were produced.
 *  @param statusMessage  Diagnostic detail for failed sweeps.
 */
case class EvalResult(
    providerLabel: String,
    sessionType:   String,
    markdownPath:  Path,
    markdownScore: MarkdownScore,
    answers:       List[AnswerResult],
    status:        SweepStatus = SweepStatus.Ok,
    statusMessage: String      = ""
)

/**
 *  Reusable harness for end-to-end document QA evaluation.
 *
 *  Given a PDF, a JSON question set, and a list of (LLM, VLM) configurations, the harness:
 *    1. Copies the PDF into a temporary workspace.
 *    2. Generates Markdown via the real `files_read_pdf_to_markdown` tool path.
 *    3. Scores the Markdown with [[MarkdownScorer]].
 *    4. Runs each question through the real AgentLoop.
 *    5. Judges predicted answers with an LLM-as-judge.
 *
 *  All persistent stores are in-memory stubs, so the harness does not need a
 *  running SQLite database.  Tool permissions are auto-granted so the test
 *  does not block on UI modals.
 */
object EvalHarness
{
    // Executor for eval work.  AgentLoop is designed to block directly on virtual threads,
    // and using a virtual-thread executor lets timed-out work be interrupted cleanly instead
    // of tying up the Scala global fork-join pool.
    private val evalExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

    Runtime.getRuntime.addShutdownHook(new Thread(() => { evalExecutor.shutdownNow(); () }))

    /** Returns an ISO timestamp for evaluation lifecycle logs. */
    private def ts(): String = java.time.Instant.now().toString
    /** Writes a timestamped evaluation lifecycle message. */
    private def log(msg: String): Unit = println(s"[${ts()}] $msg")

    /**
     *  Creates a directory `pdf-eval-YYYYMMDD-HHMMSS` under the persistent Agentica
     *  eval state directory. On collision (two runs started within the same second,
     *  e.g. back-to-back tests) a `-N` counter suffix is appended.
     */
    private def createEvalWorkDir(): Path =
    {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val stamp       = java.time.LocalDateTime.now().format(formatter)
        Files.createDirectories(AppDirs.evalsDir)
        Iterator.iterate(0)(_ + 1)
            .map(i => AppDirs.evalsDir.resolve(if i == 0 then s"pdf-eval-$stamp" else s"pdf-eval-$stamp-$i"))
            .find { dir =>
                try { Files.createDirectory(dir); true }
                catch { case _: java.nio.file.FileAlreadyExistsException => false }
            }
            .get
    }

    // ─── In-memory store stubs ─────────────────────────────────────────────────

    /**
     *  Captures messages appended during an agent run.
     */
    private class CapturingMessageStore extends MessageStore(() => null)
    {
        val appended: mutable.ListBuffer[Message] = mutable.ListBuffer.empty

        override def append(sessionId: String, role: MessageRole, content: String): Message =
        {
            val m = Message(
                id        = s"msg-${appended.size}",
                sessionId = sessionId,
                role      = role,
                content   = content,
                timestamp = ""
            )
            appended.append(m)
            m
        }

        override def appendMessage(message: Message): Message =
        {
            val persisted = message.copy(id = s"msg-${appended.size}")
            appended.append(persisted)
            persisted
        }

        override def listForSession(sessionId: String): List[Message] = appended.toList
    }

    /**
     *  Captures tool runs without persisting them.
     */
    private class CapturingRunStore extends RunStore(() => null)
    {
        private val runs: mutable.ListBuffer[ToolRun] = mutable.ListBuffer.empty

        override def insertRun(run: ToolRun): Unit = this.synchronized {
            runs.append(run)
        }

        def forTrace(traceId: String): List[ToolRun] = this.synchronized {
            runs.filter(_.traceId == traceId).toList
        }
    }

    private case class EvalAgent(loop: AgentLoop, runStore: CapturingRunStore)

    /**
     *  No-op TokenAccounting that simply counts records.
     */
    private class StubTokenAccounting extends TokenAccounting(null.asInstanceOf[RunStore])
    {
        var recordCount = 0

        override def record(traceId: String, sessionId: String, llmResponse: LLMResponse): Unit =
        {
            recordCount += 1
        }
    }

    /**
     *  Auto-grants every permission request.  Used so tests do not need a UI.
     */
    private class AutoGrantScopeStore extends ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = true
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /**
     *  In-memory session store that accepts response-ID updates without a database.
     */
    private class StubSessionStore extends SessionStore(() => null)
    {
        override def updateLastResponseId(id: String, responseId: String): Unit = ()
    }

    /**
     *  In-memory AgentTurn store that discards records.
     */
    private class StubAgentTurnStore extends AgentTurnStore(null)
    {
        override def insert(t: AgentTurn): Unit = ()
    }

    /**
     *  In-memory MemoryStore backed by a mutable map.
     */
    private class StubMemoryStore extends MemoryStore
    {
        private val data = mutable.Map.empty[(String, String), MemoryEntry]

        def init(): Unit = ()

        def set(sessionId: String, key: String, value: String): MemoryEntry =
        {
            val entry = MemoryEntry(sessionId, key, value, Instant.now().toString)
            data.put((sessionId, key), entry)
            entry
        }

        def get(sessionId: String, key: String): Option[MemoryEntry] = data.get((sessionId, key))

        def list(sessionId: String): List[MemoryEntry] =
            data.filter(_._1._1 == sessionId).values.toList.sortBy(_.key)

        def deleteForSession(sessionId: String): Unit =
            data.filterInPlace((k, _) => k._1 != sessionId)
    }

    // ─── Agent / tool wiring ─────────────────────────────────────────────────────

    private var toolIndexApplied = false

    /**
     *  Idempotently substitutes `{{TOOL_INDEX}}` in the global system prompt.
     *  Must be called after the full command registry is built.
     */
    private def ensureToolIndexApplied(): Unit =
    {
        if (!toolIndexApplied)
        {
            ContextManager.applyToolIndex(buildCommandRegistry().helpIndex)
            toolIndexApplied = true
        }
    }

    /**
     *  Builds a CommandRegistry containing all production tools used by the eval.
     */
    private def buildCommandRegistry(): CommandRegistry =
    {
        val registry = CommandRegistry()
        registry.register(FilesRead)
        registry.register(FilesWrite)
        registry.register(FilesList)
        registry.register(FilesSearch)
        registry.register(FilesStat)
        registry.register(MemorySet)
        registry.register(MemoryGet)
        registry.register(MemoryList)
        registry.register(FilesReadPDFToMarkdown)
        registry.register(FilesReadDOCXToMarkdown)
        registry.register(FilesReadPPTXToMarkdown)
        registry
    }

    /**
     *  Creates an AgentLoop wired with in-memory stubs and the real tool registry.
     *
     *  @param llm       Primary LLM provider.
     *  @param vlm       Optional VLM provider.
     *  @param settings  AppSettings for iterations, budget, debug mode, etc.
     *  @return          A ready-to-run agent and its authoritative tool-run store.
     */
    private def makeAgentLoop(
        llm:      LLMProvider,
        vlm:      Option[LLMProvider],
        settings: AppSettings
    ): EvalAgent =
    {
        ensureToolIndexApplied()
        val runStore = new CapturingRunStore()
        val loop = new AgentLoop(
            initialLLMProvider = llm,
            initialVLMProvider = vlm,
            messageStore       = new CapturingMessageStore(),
            runStore           = runStore,
            agentTurnStore     = new StubAgentTurnStore(),
            tokenAccounting    = new StubTokenAccounting(),
            virtualShell       = new VirtualShell(buildCommandRegistry()),
            settings           = settings,
            scopeStore         = new AutoGrantScopeStore(),
            memoryStore        = new StubMemoryStore(),
            sessionStore       = new StubSessionStore()
        )
        EvalAgent(loop, runStore)
    }

    /**
     *  Builds a fresh Session pointing at the given workspace directory.
     */
    def makeSession(workspace: Path): Session =
    {
        Session(
            id        = UUID.randomUUID().toString,
            title     = "Eval Session",
            createdAt = Instant.now().toString,
            updatedAt = Instant.now().toString,
            model     = "eval-model",
            rootPath  = Some(workspace.toString)
        )
    }

    // ─── Document conversion and scoring ───────────────────────────────────────────

    /**
     *  Converts the PDF in `pdfPath` to Markdown using the real document tool.
     *  Returns the generated Markdown content and the relative Markdown path.
     *
     *  @param pdfPath   Absolute path to the PDF inside the workspace.
     *  @param session   Session whose rootPath is the PDF's parent workspace.
     *  @param llm       Primary LLM provider (used as VLM fallback if vlm is None).
     *  @param vlm       Optional dedicated VLM provider.
     *  @param settings  Settings controlling debug mode and VLM parallelism.
     *  @return          Tuple of (markdown content, relative markdown path).
     */
    def convertPDFToMarkdown(
        pdfPath:  Path,
        session:  Session,
        llm:      LLMProvider,
        vlm:      Option[LLMProvider],
        settings: AppSettings
    ): (String, String) =
    {
        val relPDF = session.rootPath match
        {
            case Some(root) => Paths.get(root).toAbsolutePath.normalize()
                .relativize(pdfPath.toAbsolutePath.normalize()).toString
            case None => pdfPath.getFileName.toString
        }

        val input = FilesReadPDFToMarkdownInput(
            path         = Paths.get(relPDF),
            enrichImages = ConversionEnrichImages
        )

        val ctx = agentica.tools.ExecutionContext(
            session         = session,
            traceId         = s"eval-pdf-${session.id}",
            scopeStore      = new AutoGrantScopeStore(),
            scratchpad      = SessionScratchpad(),
            memoryStore     = new StubMemoryStore(),
            llmProvider     = llm,
            vlmProvider     = vlm,
            onEvent         = _ => (),
            permissionCoordinator = new PermissionCoordinator("eval-tool-run"),
            debugMode       = settings.debugMode,
            vlmParallelism  = settings.vlmParallelism
        )

        val output = FilesReadPDFToMarkdown.execute(input, ctx)
        if (output.error.isDefined)
        {
            throw new RuntimeException(
                s"PDF conversion failed: ${output.error.getOrElse("unknown error")}"
            )
        }

        val mdPath = pdfPath.getParent.resolve(output.markdownPath)
        val markdown = Files.readString(mdPath)
        (markdown, output.markdownPath)
    }

    /**
     *  Scores generated Markdown against the PDFBox reference text.
     *
     *  @param markdown  VLM-generated Markdown string.
     *  @param pdfPath   Source PDF path (for PDFBox text extraction and page count).
     *  @return          MarkdownScore with all sub-metrics.
     */
    def scoreMarkdown(markdown: String, pdfPath: Path): MarkdownScore =
    {
        val referenceText = MarkdownScorer.extractPDFText(pdfPath)
        val pageCount     = PDFPageRenderer.pageCount(pdfPath)
        MarkdownScorer.score(markdown, referenceText, pageCount)
    }

    // ─── Question answering ────────────────────────────────────────────────────────

    /**
     *  Asks the agent a single question, letting it read the generated Markdown file.
     *
     *  @param question        The user question.
     *  @param markdownRelPath Relative path to the Markdown file within the workspace.
     *  @param agent           Pre-built eval agent and its tool-run store.
     *  @param session         Session whose rootPath is the workspace.
     *  @param history         Prior conversation history (empty for isolated sessions).
     *  @param timeoutMs       Hard wall-clock timeout for the turn.
     *  @return                [[TurnResult]] with answer text, tools, and outcome.
     */
    private def answerQuestion(
        question:        String,
        markdownRelPath: String,
        agent:           EvalAgent,
        session:         Session,
        history:         List[Message] = Nil,
        timeoutMs:       Long = 120000L
    ): TurnResult =
    {
        val userMsg = Message(
            id        = s"q-${UUID.randomUUID()}",
            sessionId = session.id,
            role      = MessageRole.User,
            content   = s"""Answer the following question using the converted Markdown document at path="$markdownRelPath".
                           |
                           |Question: $question
                           |
                           |Be concise and base your answer only on the document.
                           |""".stripMargin,
            timestamp = Instant.now().toString
        )

        runAgentTurn(agent, session, history, userMsg, timeoutMs)
    }

    /** Minimum pause before a timeout retry. */
    private val RetryDelayMinMs: Long = 5000L

    /** Random jitter added on top of [[RetryDelayMinMs]] (delays land in 5–10s). */
    private val RetryDelayJitterMs: Int = 5000

    /**
     *  Picks the delay before a retry: a caller-supplied fixed value wins,
     *  otherwise `minMs` plus uniform jitter up to `jitterMs`.
     *
     *  @param fixed     Optional fixed delay override.
     *  @param minMs     Minimum delay when randomising.
     *  @param jitterMs  Upper bound of the random component.
     *  @return          Delay in milliseconds.
     */
    private def pickRetryDelay(fixed: Option[Long], minMs: Long, jitterMs: Int): Long =
        fixed.getOrElse(minMs + scala.util.Random.nextInt(jitterMs))

    /**
     *  Asks a question, retrying exactly once after a randomised delay when the
     *  first attempt times out.  The retry reuses the same session and history so
     *  shared-session sweeps keep their intended context and ordering.
     *
     *  @param retryDelayMs  Fixed delay before the retry; `None` picks a random 5–10s delay.
     *  @return              Final [[TurnResult]]; `attempts` is 2 when a retry was used.
     */
    private def answerQuestionWithRetry(
        question:        String,
        markdownRelPath: String,
        agent:           EvalAgent,
        session:         Session,
        history:         List[Message],
        timeoutMs:       Long,
        retryDelayMs:    Option[Long]
    ): TurnResult =
    {
        val first = answerQuestion(question, markdownRelPath, agent, session, history, timeoutMs)
        if first.status != AnswerStatus.Timeout then return first

        val delayMs = pickRetryDelay(retryDelayMs, RetryDelayMinMs, RetryDelayJitterMs)
        log(s"  Retrying timed-out question after ${delayMs}ms: ${question.take(60)}")
        Thread.sleep(delayMs)
        val retried = answerQuestion(question, markdownRelPath, agent, session, history, timeoutMs)
        retried.copy(attempts = 2)
    }

    /**
     *  Result of one QA agent turn: captured answer text, thoughts,
     *  tool usage, and the turn outcome.
     */
    private case class TurnResult(
        actual:     String,
        thoughts:   String,
        toolCalls:  List[String],
        toolCounts: Map[String, Int],
        status:     AnswerStatus,
        attempts:   Int = 1
    )

    /**
     *  Returns true if `t` or any exception in its cause chain represents a timeout
     *  (turn-level TimeoutException, HTTP request timeout, or socket timeout).
     *  Exceptions thrown inside `future.get` arrive wrapped in ExecutionException,
     *  so the cause chain must be walked.
     */
    private def isTimeoutCause(t: Throwable): Boolean =
    {
        var cur: Throwable = t
        while cur != null do
        {
            cur match
            {
                case _: TimeoutException                   => return true
                case _: java.net.http.HttpTimeoutException => return true
                case _: java.net.SocketTimeoutException    => return true
                case _                                     => ()
            }
            val next = cur.getCause
            cur = if (next eq cur) then null else next
        }
        false
    }

    /**
     *  Runs one agent turn with a hard timeout and returns the final answer text,
     *  intermediate thoughts, ordered tool calls, and tool call counts.
     *
     *  The agent's full token stream is split at the last [[AgentEvent.LLMCallStart]]
     *  boundary: everything before it is "thoughts" (reasoning from earlier iterations),
     *  and everything from that point on (minus `<done>`) is the "actual answer".
     *
     *  @param agent      AgentLoop and authoritative ToolRun store.
     *  @param session    Active session.
     *  @param history    Prior conversation history.
     *  @param userMsg    Current user message.
     *  @param timeoutMs  Maximum duration to wait.
     *  @return           [[TurnResult]] with answer text, tools, and outcome.
     */
    private def runAgentTurn(
        agent:     EvalAgent,
        session:   Session,
        history:   List[Message],
        userMsg:   Message,
        timeoutMs: Long
    ): TurnResult =
    {
        val cancelFlag = new AtomicBoolean(false)
        val traceId    = s"eval-qa-${UUID.randomUUID()}"
        val permissionCoordinator = new PermissionCoordinator(traceId)
        val answerBuf       = new StringBuilder()
        val lastLLMStartPos = new java.util.concurrent.atomic.AtomicInteger(0)
        val terminalEvent   = new AtomicReference[Option[AgentEvent]](None)

        log(s"Agent turn start traceId=$traceId session=${session.id} timeout=${timeoutMs}ms")
        val t0 = System.currentTimeMillis()

        val future = evalExecutor.submit(new java.util.concurrent.Callable[Unit]
        {
            override def call(): Unit =
                agent.loop.run(
                    session         = session,
                    history         = history,
                    userMsg         = userMsg,
                    traceId         = traceId,
                    cancelFlag      = cancelFlag,
                    permissionCoordinator = permissionCoordinator,
                    emitToken       = tok => answerBuf.append(tok),
                    emitEvent       =
                    {
                        case AgentEvent.LLMCallStart(_, _, _) =>
                            lastLLMStartPos.set(answerBuf.length)
                        case event @ (AgentEvent.Final(_, _) | AgentEvent.AgentError(_) | AgentEvent.Cancelled) =>
                            terminalEvent.set(Some(event))
                        case _ =>
                            ()
                    }
                )
        })

        def splitAnswer: (String, String) =
        {
            val full   = answerBuf.toString().replace("<done>", "")
            val split  = lastLLMStartPos.get()
            val thoughts = full.substring(0, split).trim
            val actual   = full.substring(split).trim match
            {
                case "" => "[No final answer captured]"
                case s  => s
            }
            (actual, thoughts)
        }

        def capturedTools: (List[String], Map[String, Int]) =
        {
            val runs = agent.runStore.forTrace(traceId)
            val calls = runs.map(_.input)
            val counts = runs.groupMapReduce(_.tool)(_ => 1)(_ + _)
            (calls, counts)
        }

        try
        {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = System.currentTimeMillis() - t0
            log(s"Agent turn complete traceId=$traceId elapsed=${elapsed}ms")
            val (actual, thoughts) = splitAnswer
            val (toolCalls, toolCounts) = capturedTools
            terminalEvent.get() match
            {
                case Some(AgentEvent.Final(_, _)) =>
                    TurnResult(actual, thoughts, toolCalls, toolCounts, AnswerStatus.Answered)
                case Some(AgentEvent.AgentError(message)) =>
                    log(s"Agent turn terminal error traceId=$traceId: $message")
                    TurnResult(s"[Agent error: $message]", thoughts, toolCalls, toolCounts, AnswerStatus.Error)
                case Some(AgentEvent.Cancelled) =>
                    TurnResult("[Agent turn cancelled]", thoughts, toolCalls, toolCounts, AnswerStatus.Error)
                case _ =>
                    TurnResult("[Agent turn ended without a terminal event]", thoughts, toolCalls, toolCounts, AnswerStatus.Error)
            }
        }
        catch
        {
            case _: TimeoutException =>
                val elapsed = System.currentTimeMillis() - t0
                log(s"Agent turn TIMEOUT traceId=$traceId elapsed=${elapsed}ms; cancelling worker")
                cancelFlag.set(true)
                future.cancel(true)
                permissionCoordinator.close()
                val (actual, thoughts) = splitAnswer
                val (toolCalls, toolCounts) = capturedTools
                TurnResult(actual, thoughts, toolCalls, toolCounts, AnswerStatus.Timeout)
            case t: Throwable =>
                log(s"Agent turn ERROR traceId=$traceId ${t.getClass.getSimpleName}: ${t.getMessage}")
                future.cancel(true)
                permissionCoordinator.close()
                val (toolCalls, toolCounts) = capturedTools
                val status = if isTimeoutCause(t) then AnswerStatus.Timeout else AnswerStatus.Error
                TurnResult(s"[Agent turn error: ${t.getClass.getSimpleName}: ${t.getMessage}]", "", toolCalls, toolCounts, status)
        }
    }

    // ─── LLM-as-judge ─────────────────────────────────────────────────────────────

    /**
     *  Judge a predicted answer against a reference answer using the given LLM.
     *
     *  The judge is instructed to return only a JSON object:
     *    {"score": 0.9, "rationale": "..."}
     *
     *  @param question           Original question.
     *  @param referenceAnswer    Ground-truth answer.
     *  @param predictedAnswer    Agent-predicted answer.
     *  @param judgeProvider      LLM provider used as the judge.
     *  @param timeoutMs          Maximum time to wait for the judge.
     *  @return                   Tuple of (verdict if any, rationale, judge status).
     */
    def judgeAnswer(
        question:        String,
        referenceAnswer: String,
        predictedAnswer: String,
        judgeProvider:   LLMProvider,
        timeoutMs:       Long = 60000L
    ): (Option[JudgeVerdict], String, JudgeStatus) =
    {
        val prompt =
            s"""You are an expert evaluator. Compare the predicted answer to the reference answer for the given question.
               |Judge how factually correct and complete the predicted answer is, based only on the reference.
               |
               |Question: $question
               |Reference Answer: $referenceAnswer
               |Predicted Answer: $predictedAnswer
               |
               |Return ONLY a JSON object in this exact format:
               |{"verdict": "correct", "rationale": "Brief explanation"}
               |
               |The "verdict" field must be exactly one of:
               |- "correct": fully correct and complete
               |- "partial": partially correct or missing important details
               |- "wrong": incorrect, unsupported, or hallucinated
               |""".stripMargin

        val systemMsg = Message(
            id        = "judge-system",
            sessionId = "",
            role      = MessageRole.System,
            content   = "You are a strict factual evaluator. Return only JSON.",
            timestamp = ""
        )
        val userMsg = Message(
            id        = "judge-user",
            sessionId = "",
            role      = MessageRole.User,
            content   = prompt,
            timestamp = ""
        )

        var attempt = 1
        var result  = judgeAttempt(systemMsg, userMsg, judgeProvider, timeoutMs, attempt)
        while (result._3 == JudgeStatus.JudgeError && attempt < JudgeMaxAttempts)
        {
            val delayMs = pickRetryDelay(None, JudgeRetryMinMs, JudgeRetryJitterMs)
            log(s"Judge attempt $attempt failed (${result._2}); retrying in ${delayMs}ms")
            Thread.sleep(delayMs)
            attempt += 1
            result = judgeAttempt(systemMsg, userMsg, judgeProvider, timeoutMs, attempt)
        }
        result
    }

    /** Total judge attempts per answer; a retry absorbs transient engine/protocol errors. */
    private val JudgeMaxAttempts: Int = 2

    /** Minimum pause between judge attempts. */
    private val JudgeRetryMinMs: Long = 500L

    /** Random jitter added on top of [[JudgeRetryMinMs]] (delays land in 0.5–1s). */
    private val JudgeRetryJitterMs: Int = 500

    /**
     *  Performs one judge call and classifies the outcome.
     *
     *  Transport errors and unparsable content map to [[JudgeStatus.JudgeError]]
     *  (retryable by the caller); timeouts map to [[JudgeStatus.JudgeTimeout]]
     *  and are not retried because a hung endpoint is likely to hang again.
     *
     *  @param systemMsg     Judge system prompt message.
     *  @param userMsg       Judge user prompt message.
     *  @param judgeProvider LLM provider used as the judge.
     *  @param timeoutMs     Maximum time to wait for this attempt.
     *  @param attempt       1-based attempt number for logging.
     *  @return              Tuple of (verdict if any, rationale, judge status).
     */
    private def judgeAttempt(
        systemMsg:     Message,
        userMsg:       Message,
        judgeProvider: LLMProvider,
        timeoutMs:     Long,
        attempt:       Int
    ): (Option[JudgeVerdict], String, JudgeStatus) =
    {
        log(s"Judge call start attempt=$attempt timeout=${timeoutMs}ms")
        val t0 = System.currentTimeMillis()
        val future = evalExecutor.submit(new java.util.concurrent.Callable[String]
        {
            override def call(): String =
            {
                val buf = new StringBuilder()
                val response = judgeProvider.streamChatCompletions(List(systemMsg, userMsg), tok => buf.append(tok))
                if (buf.isEmpty && response.toolCalls.nonEmpty)
                {
                    throw IllegalStateException(
                        s"Judge returned ${response.toolCalls.size} unexpected tool call(s) with no content")
                }
                buf.toString()
            }
        })

        try
        {
            val responseText = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            log(s"Judge call complete attempt=$attempt elapsed=${System.currentTimeMillis() - t0}ms")
            val (verdict, rationale) = parseJudgeResponse(responseText)
            (verdict, rationale, if verdict.isDefined then JudgeStatus.Judged else JudgeStatus.JudgeError)
        }
        catch
        {
            case t: Throwable if isTimeoutCause(t) =>
                log(s"Judge call TIMEOUT after ${timeoutMs}ms; cancelling worker")
                future.cancel(true)
                (None, "Judge call timed out", JudgeStatus.JudgeTimeout)
            case t: Throwable =>
                log(s"Judge call ERROR attempt=$attempt ${t.getClass.getSimpleName}: ${t.getMessage}")
                future.cancel(true)
                (None, s"Judge error: ${t.getClass.getSimpleName}: ${t.getMessage}", JudgeStatus.JudgeError)
        }
    }

    /**
     *  Parses the judge JSON response into (verdict, rationale).
     *  Reads the categorical `verdict` field; falls back to bucketing a numeric
     *  `score` field for judges that ignore the requested format.  Returns
     *  `None` verdict only when nothing usable was returned.
     */
    private def parseJudgeResponse(text: String): (Option[JudgeVerdict], String) =
    {
        try
        {
            // Find the first '{' ... '}' substring to tolerate surrounding text.
            val jsonMatch = raw"\{.*\}".r.findFirstIn(text.replaceAll("\\s+", " "))
            jsonMatch match
            {
                case Some(jsonStr) =>
                    val json = ujson.read(jsonStr)
                    val verdict = json.obj.get("verdict")
                        .flatMap(v => JudgeVerdict.parse(v.str))
                        .orElse(json.obj.get("score").map(s => JudgeVerdict.fromScore(s.num)))
                    val rationale = json.obj.get("rationale").map(_.str).getOrElse("No rationale provided")
                    verdict match
                    {
                        case Some(v) => (Some(v), rationale)
                        case None    => (None, s"Unrecognized judge response: $rationale")
                    }
                case None =>
                    (None, s"No JSON object found in judge response: $text")
            }
        }
        catch
        {
            case t: Throwable =>
                (None, s"Failed to parse judge response: ${t.getMessage}. Raw: $text")
        }
    }

    // ─── High-level orchestration ──────────────────────────────────────────────────

    /**
     *  Saves all judged question/answer pairs for a sweep to a single JSON file.
     *
     *  @param sweepDir  Directory for this provider sweep.
     *  @param answers   All judged question results for this sweep.
     */
    private def saveQuestions(sweepDir: Path, answers: List[AnswerResult]): Unit =
    {
        val json = Arr(answers.map { a =>
            Obj(
                "question"        -> Str(a.question),
                "category"        -> Str(a.category),
                "referenceAnswer" -> Str(a.referenceAnswer),
                "actualAnswer"    -> Str(a.actualAnswer),
                "thoughts"        -> Str(a.thoughts),
                "toolCalls"       -> Arr(a.toolCalls.map(Str(_))*),
                "toolCounts"      -> Obj.from(a.toolCounts.map { case (k, v) => k -> Num(v) }),
                "judgeScore"      -> Num(a.judgeScore),
                "judgeRationale"  -> Str(a.judgeRationale),
                "verdict"         -> a.judgeVerdict.map(v => Str(v.label)).getOrElse(ujson.Null),
                "status"          -> Str(a.status.label),
                "judgeStatus"     -> Str(a.judgeStatus.label),
                "attempts"        -> Num(a.attempts)
            )
        }*)
        Files.writeString(sweepDir.resolve("questions.json"), json.render(indent = 2))
    }

    /**
     *  Saves the non-sensitive provider configuration metadata to a manifest file.
     *  This lets reporting tools recover which models, endpoints, and session types
     *  were used for each sweep without re-reading the original provider config.
     *
     *  @param providerDir  Directory for this provider sweep.
     *  @param manifest     Provider metadata to persist.
     */
    private def saveProviderManifest(providerDir: Path, manifest: ProviderManifest): Unit =
    {
        val json = Obj(
            "providerLabel"     -> Str(manifest.providerLabel),
            "llmBaseURL"        -> Str(manifest.llmBaseURL),
            "llmModel"          -> Str(manifest.llmModel),
            "vlmBaseURL"        -> manifest.vlmBaseURL.map(Str(_)).getOrElse(ujson.Null),
            "vlmModel"          -> manifest.vlmModel.map(Str(_)).getOrElse(ujson.Null),
            "judgeBaseURL"      -> manifest.judgeBaseURL.map(Str(_)).getOrElse(ujson.Null),
            "judgeModel"        -> manifest.judgeModel.map(Str(_)).getOrElse(ujson.Null),
            "sessionTypes"      -> Arr(manifest.sessionTypes.map(Str(_))*),
            "questionTimeoutMs" -> Num(manifest.questionTimeoutMs),
            "pdfTimeoutMs"      -> Num(manifest.pdfTimeoutMs),
            "reuseMarkdownCache" -> Bool(manifest.reuseMarkdownCache),
            "markdownCache"     -> manifest.markdownCache.map { c =>
                Obj(
                    "enabled"   -> Bool(c.enabled),
                    "hit"       -> Bool(c.hit),
                    "key"       -> Str(c.key),
                    "pdfSha256" -> Str(c.pdfSha256),
                    "source"    -> Str(c.source)
                )
            }.getOrElse(ujson.Null)
        )
        Files.writeString(providerDir.resolve("manifest.json"), json.render(indent = 2))
    }

    /**
     *  Saves a sweep summary (Markdown score, average judge score, tool counts) as JSON.
     *
     *  @param sweepDir      Directory for this provider sweep.
     *  @param fileStem      Document filename without extension.
     *  @param providerLabel Provider config label.
     *  @param mdScore       Markdown quality score.
     *  @param answers       All judged question results for this sweep.
     */
    private def saveSummary(
        sweepDir:          Path,
        fileStem:      String,
        providerLabel: String,
        mdScore:       MarkdownScore,
        answers:       List[AnswerResult]
    ): Unit =
    {
        val judged   = answers.filter(_.judgeStatus == JudgeStatus.Judged)
        val avgScore = if (judged.isEmpty) 0.0 else judged.map(_.judgeScore).sum / judged.length
        val totalToolCounts = answers.foldLeft(Map.empty[String, Int]) { (acc, r) =>
            r.toolCounts.foldLeft(acc) { case (m, (k, v)) => m.updated(k, m.getOrElse(k, 0) + v) }
        }
        val json = Obj(
            "providerLabel" -> Str(providerLabel),
            "pdfFile"       -> Str(fileStem),
            "markdownScore" -> Obj(
                "wordCountRatio" -> Num(mdScore.wordCountRatio),
                "tokenOverlap"   -> Num(mdScore.tokenOverlap),
                "headingCount"   -> Num(mdScore.headingCount),
                "tableRowCount"  -> Num(mdScore.tableRowCount),
                "nonEmptyPages"  -> Num(mdScore.nonEmptyPages),
                "totalPages"     -> Num(mdScore.totalPages),
                "nonEmptyRatio"  -> Num(mdScore.nonEmptyRatio),
                "refWordCount"   -> Num(mdScore.refWordCount),
                "vlmWordCount"   -> Num(mdScore.vlmWordCount)
            ),
            "averageJudgeScore" -> Num(avgScore),
            "judgedCount"       -> Num(judged.length),
            "verdicts"          -> Obj(
                "correct" -> Num(judged.count(_.judgeVerdict.contains(JudgeVerdict.Correct))),
                "partial" -> Num(judged.count(_.judgeVerdict.contains(JudgeVerdict.PartiallyCorrect))),
                "wrong"   -> Num(judged.count(_.judgeVerdict.contains(JudgeVerdict.Wrong)))
            ),
            "answeredCount"     -> Num(answers.count(_.status == AnswerStatus.Answered)),
            "timeoutCount"      -> Num(answers.count(_.status == AnswerStatus.Timeout)),
            "errorCount"        -> Num(answers.count(_.status == AnswerStatus.Error)),
            "judgeTimeoutCount" -> Num(answers.count(_.judgeStatus == JudgeStatus.JudgeTimeout)),
            "judgeErrorCount"   -> Num(answers.count(_.judgeStatus == JudgeStatus.JudgeError)),
            "retriedCount"      -> Num(answers.count(_.attempts > 1)),
            "totalToolCounts"   -> Obj.from(totalToolCounts.map { case (k, v) => k -> Num(v) }),
            "questionsFile"     -> Str("questions.json")
        )
        Files.writeString(sweepDir.resolve("summary.json"), json.render(indent = 2))
    }

    /** Default pause (ms) between answer generation and judging when the judge endpoint is local. */
    private val DefaultLocalPauseMs: Long = 30000L

    /** Version marker invalidating entries when the conversion pipeline or prompt changes. */
    private val MarkdownCacheVersion = "vision-v1"

    /** Whether PDF conversion requests image enrichment; part of the cache key. */
    private val ConversionEnrichImages: Boolean = true

    /** Minimum size for a cache entry to be trusted as a completed conversion. */
    private val MinMarkdownCacheBytes: Long = 100L

    /**
     *  Returns the directory holding content-addressed Markdown cache entries.
     *  Defaults to `.markdown-cache` under the evals state directory and can be
     *  redirected with the `AGENTICA_MARKDOWN_CACHE_DIR` system property or
     *  environment variable (useful for tests and CI caches).
     *
     *  @return  Directory path in which `<key>.md` entries live.
     */
    private[eval] def markdownCacheDir: Path =
        sys.props.get("AGENTICA_MARKDOWN_CACHE_DIR")
            .orElse(sys.env.get("AGENTICA_MARKDOWN_CACHE_DIR"))
            .map(Paths.get(_))
            .getOrElse(AppDirs.evalsDir.resolve(".markdown-cache"))

    /** Returns a lowercase SHA-256 digest for the supplied bytes. */
    private def sha256(bytes: Array[Byte]): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

    /**
     *  Computes the content-addressed cache entry for one PDF and conversion
     *  identity (VLM endpoint and model).  The key covers the conversion
     *  pipeline version, image-enrichment behavior, and the PDF bytes, so
     *  different documents or converter settings never collide.
     *
     *  @param pdfPath   Source PDF whose bytes are hashed.
     *  @param identity  Converter identity string (e.g. `vlmBaseURL|vlmModel`).
     *  @return          Tuple of (cache file path, cache key, PDF SHA-256).
     */
    private[eval] def markdownCacheEntry(pdfPath: Path, identity: String): (Path, String, String) =
    {
        val pdfHash = sha256(Files.readAllBytes(pdfPath))
        val key = sha256(
            s"$MarkdownCacheVersion|enrichImages=$ConversionEnrichImages|$pdfHash|$identity"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        (Files.createDirectories(markdownCacheDir).resolve(s"$key.md"), key, pdfHash)
    }

    /** Writes a cache entry atomically so interrupted conversions cannot leave partial Markdown. */
    private def writeMarkdownCache(path: Path, markdown: String): Unit =
    {
        val temp = Files.createTempFile(path.getParent, path.getFileName.toString, ".tmp")
        Files.writeString(temp, markdown)
        try Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Zeroed MarkdownScore used for sweeps that failed before conversion produced output. */
    private val EmptyMarkdownScore = MarkdownScore(0.0, 0.0, 0, 0, 0, 0, 0.0, 0, 0)

    /**
     *  Bundle of live providers and session types used by [[runEvalBundles]].
     *
     *  @param label                Human-readable identifier.
     *  @param llm                  Primary LLM provider.
     *  @param vlm                  Optional vision LLM provider.
     *  @param judge                Judge LLM provider.
     *  @param sessionTypes         Session allocation strategies to run.
     *  @param pauseBetweenPhasesMs Optional pause before judging.
     *  @param beforeJudge          Hook executed between answer generation and judging;
     *                              used by config-based runs for LM-Studio model unloading.
     *  @param reuseMarkdownCache   Whether a matching content-addressed Markdown cache entry may be reused.
     *  @param markdownCacheIdentity Stable VLM identity included in the conversion cache key.
     */
    case class EvalProviderBundle(
        label:                String,
        llm:                  LLMProvider,
        vlm:                  Option[LLMProvider],
        judge:                LLMProvider,
        sessionTypes:         List[EvalSessionType] = List(EvalSessionType.AllQuestionsPerSession),
        pauseBetweenPhasesMs: Option[Long]            = None,
        beforeJudge:          () => Unit               = () => (),
        metadata:             Option[ProviderManifest]  = None,
        reuseMarkdownCache:   Boolean                   = true,
        markdownCacheIdentity: String                   = "default"
    )

    /**
     *  Runs the full evaluation pipeline for one PDF against a list of provider bundles.
     *
     *  This is the provider-agnostic core used by both config-driven runs and smoke tests.
     *
     *  @param pdfPath           Source PDF to evaluate.
     *  @param questions         List of EvalQuestion entries.
     *  @param bundles           Provider bundles to test.
     *  @param settings          AppSettings used for AgentLoop and VLM options.
     *  @param pdfTimeoutMs      Timeout for PDF-to-Markdown conversion.
     *  @param questionTimeoutMs Timeout for each QA agent turn.
     *  @param retryDelayMs      Fixed delay before a question timeout retry;
     *                           `None` picks a random 5–10s delay.
     *  @return                  Tuple of (root work directory, list of EvalResult).
     */
    def runEvalBundles(
        pdfPath:           Path,
        questions:         List[EvalQuestion],
        bundles:           List[EvalProviderBundle],
        settings:          AppSettings,
        pdfTimeoutMs:      Long = 600000L,
        questionTimeoutMs: Long = 120000L,
        retryDelayMs:      Option[Long] = None
    ): (Path, List[EvalResult]) =
    {
        ensureToolIndexApplied()

        val rootWorkDir = createEvalWorkDir()
        log(s"Root work directory: $rootWorkDir")

        val pdfName   = pdfPath.getFileName.toString
        val fileStem  = if (pdfName.toLowerCase.endsWith(".pdf")) pdfName.dropRight(4) else pdfName
        val fileDir   = Files.createDirectory(rootWorkDir.resolve(fileStem))
        Files.copy(pdfPath, fileDir.resolve(pdfName), StandardCopyOption.REPLACE_EXISTING)

        val allResults = bundles.flatMap { bundle =>
            log(s"=== Provider: ${bundle.label} | session types: ${bundle.sessionTypes.map(_.label).mkString(", ")} ===")

            val providerDir = fileDir.resolve(bundle.label)
            if !Files.exists(providerDir) then
            {
                Files.createDirectory(providerDir)
            }
            bundle.metadata.foreach { m => saveProviderManifest(providerDir, m) }

            try
            {
                val llm = bundle.llm
                val vlm = bundle.vlm
                val agent = makeAgentLoop(llm, vlm, settings)

                // Convert PDF to Markdown once per provider (VLM-intensive).
                val workPDF = providerDir.resolve(pdfName)
                Files.copy(fileDir.resolve(pdfName), workPDF, StandardCopyOption.REPLACE_EXISTING)

                val (cachePath, cacheKey, pdfHash) = markdownCacheEntry(workPDF, bundle.markdownCacheIdentity)
                val cacheHit = bundle.reuseMarkdownCache && Files.exists(cachePath) && Files.size(cachePath) > MinMarkdownCacheBytes

                bundle.metadata.foreach { m =>
                    saveProviderManifest(providerDir, m.copy(markdownCache = Some(MarkdownCacheInfo(
                        enabled   = bundle.reuseMarkdownCache,
                        hit       = cacheHit,
                        key       = cacheKey,
                        pdfSha256 = pdfHash,
                        source    = cachePath.toString
                    ))))
                }

                val (markdown, mdRelPath) = if (cacheHit)
                {
                    log(s"--- Reusing Markdown cache: ${cachePath.getFileName} ---")
                    (Files.readString(cachePath), s"$fileStem.md")
                }
                else
                {
                    log(s"--- Converting PDF to Markdown: ${bundle.label} ---")
                    val conversionSession = makeSession(providerDir)
                    val converted = runWithTimeout(
                        convertPDFToMarkdown(workPDF, conversionSession, llm, vlm, settings),
                        pdfTimeoutMs,
                        "PDF conversion"
                    )
                    writeMarkdownCache(cachePath, converted._1)
                    converted
                }
                val mdScore = scoreMarkdown(markdown, workPDF)
                log(s"Markdown score: ${mdScore.pretty}")

                // Phase 1: generate answers for every session type.
                // Each entry is (sessionType, sweepDir, mdSavePath, mdScore, qaPairs).
                val phaseOneResults = bundle.sessionTypes.map { sessionType =>
                    log(s"--- Generating answers: ${bundle.label} / ${sessionType.label} ---")
                    val sweepDir = Files.createDirectory(providerDir.resolve(sessionType.label))

                    // Save a copy of the Markdown into each sweep directory for traceability.
                    val mdSavePath = sweepDir.resolve(s"$fileStem.md")
                    Files.writeString(mdSavePath, markdown)

                    log(s"Generating answers (${sessionType.label} session) for ${questions.length} questions...")
                    val qaPairs = generateAnswers(questions, mdRelPath, agent, sweepDir, sessionType, questionTimeoutMs, retryDelayMs)

                    (sessionType, sweepDir, mdSavePath, mdScore, qaPairs)
                }

                // Phase 2: provider-specific pre-judge hook (e.g. LM-Studio model swap).
                bundle.beforeJudge()
                bundle.pauseBetweenPhasesMs.foreach { ms =>
                    log(s"Pausing ${ms}ms before judging...")
                    Thread.sleep(ms)
                }

                // Phase 3: judge all answers across all session types.
                phaseOneResults.map { case (sessionType, sweepDir, mdSavePath, mdScore, qaPairs) =>
                    try
                    {
                        log(s"--- Judging: ${bundle.label} / ${sessionType.label} (${qaPairs.length} answers) ---")
                        val answerResults = qaPairs.map { case (q, tr) =>
                            val (verdict, rationale, jStatus) =
                                if tr.status == AnswerStatus.Answered then
                                    judgeAnswer(q.question, q.referenceAnswer, tr.actual, bundle.judge, questionTimeoutMs)
                                else
                                    (None, s"Not judged — agent turn ${tr.status.label}", JudgeStatus.Skipped)
                            println(s"    Verdict: ${verdict.map(_.label).getOrElse("n/a")} — $rationale")
                            val score = verdict.map(JudgeVerdict.score).getOrElse(0.0)
                            AnswerResult(q.question, q.category, q.referenceAnswer, tr.actual, tr.thoughts, tr.toolCalls, score, rationale, tr.toolCounts, tr.status, jStatus, verdict, tr.attempts)
                        }

                        saveQuestions(sweepDir, answerResults)
                        saveSummary(sweepDir, fileStem, bundle.label, mdScore, answerResults)

                        EvalResult(bundle.label, sessionType.label, mdSavePath, mdScore, answerResults)
                    }
                    catch
                    {
                        case t: Throwable =>
                            val status = if isTimeoutCause(t) then SweepStatus.FailedTimeout else SweepStatus.FailedError
                            val msg    = s"${t.getClass.getSimpleName}: ${t.getMessage}"
                            log(s"ERROR judging ${bundle.label}/${sessionType.label}: $msg")
                            EvalResult(bundle.label, sessionType.label, mdSavePath, mdScore, Nil, status, msg)
                    }
                }
            }
            catch
            {
                case t: Throwable =>
                    val status = if isTimeoutCause(t) then SweepStatus.FailedTimeout else SweepStatus.FailedError
                    val msg    = s"${t.getClass.getSimpleName}: ${t.getMessage}"
                    log(s"ERROR for ${bundle.label}: $msg")
                    bundle.sessionTypes.map { st =>
                        EvalResult(bundle.label, st.label, providerDir.resolve(pdfName), EmptyMarkdownScore, Nil, status, msg)
                    }
            }
        }

        log(s"Eval run complete. Root work directory: $rootWorkDir")
        (rootWorkDir, allResults)
    }

    /**
     *  Runs the full evaluation pipeline for one PDF against all provider configs.
     *
     *  For each provider, every session type configured on that provider is executed,
     *  producing one [[EvalResult]] per (provider, sessionType) combination.  Output
     *  artifacts land in `<provider-label>/<sessionType>/` under the PDF's directory.
     *
     *  @param pdfPath          Source PDF to evaluate.
     *  @param questions        List of EvalQuestion entries.
     *  @param providerConfigs  List of provider configurations to test.
     *  @param settings         AppSettings used for AgentLoop and VLM options.
     *  @param pdfTimeoutMs     Timeout for PDF-to-Markdown conversion.
     *  @param questionTimeoutMs Timeout for each QA agent turn.
     *  @return                 Tuple of (root work directory, list of EvalResult).
     */
    def runEval(
        pdfPath:           Path,
        questions:         List[EvalQuestion],
        providerConfigs:   List[EvalProviderConfig],
        settings:          AppSettings,
        pdfTimeoutMs:      Long = 600000L,
        questionTimeoutMs: Long = 120000L
    ): (Path, List[EvalResult]) =
    {
        val bundles = providerConfigs.map { config =>
            val judgeBaseURL = config.judgeBaseURL.getOrElse(config.llmBaseURL)
            val judgeAPIKey  = config.judgeAPIKey.getOrElse(config.llmAPIKey)
            EvalProviderBundle(
                label                = config.label,
                llm                  = config.llmProvider,
                vlm                  = config.vlmProvider,
                judge                = config.judgeProvider,
                sessionTypes         = config.sessionTypes,
                pauseBetweenPhasesMs = config.pauseBetweenPhasesMs,
                metadata             = Some(ProviderManifest(
                    providerLabel     = config.label,
                    llmBaseURL        = config.llmBaseURL,
                    llmModel          = config.llmModel,
                    vlmBaseURL        = config.vlmBaseURL,
                    vlmModel          = config.vlmModel,
                    judgeBaseURL      = config.judgeBaseURL,
                    judgeModel        = config.judgeModel,
                    sessionTypes      = config.sessionTypes.map(_.label),
                    questionTimeoutMs = questionTimeoutMs,
                    pdfTimeoutMs      = pdfTimeoutMs,
                    reuseMarkdownCache = config.reuseMarkdownCache
                )),
                reuseMarkdownCache   = config.reuseMarkdownCache,
                markdownCacheIdentity = s"${config.vlmBaseURL.getOrElse(config.llmBaseURL)}|${config.vlmModel.getOrElse(config.llmModel)}",
                beforeJudge          = () => {
                    if LMStudioClient.isLocal(judgeBaseURL) then
                    {
                        log(s"Pre-judge cleanup: unloading loaded models on $judgeBaseURL...")
                        LMStudioClient.unloadAllLocalModels(judgeBaseURL, judgeAPIKey)

                        val pauseMs = config.pauseBetweenPhasesMs.getOrElse(DefaultLocalPauseMs)
                        log(s"Pausing ${pauseMs}ms before judging to allow model swap...")
                        Thread.sleep(pauseMs)
                    }
                }
            )
        }
        runEvalBundles(pdfPath, questions, bundles, settings, pdfTimeoutMs, questionTimeoutMs)
    }

    /** Fixed seed used by [[EvalSessionType.AllQuestionsPerSessionShuffled]] for deterministic question reordering. */
    private val ShuffleSeed: Long = 42L

    /**
     *  Generates predicted answers for all questions, respecting the session type.
     *
     *  - [[EvalSessionType.OneQuestionPerSession]]: each question in an independent session.
     *  - [[EvalSessionType.AllQuestionsPerSession]]: one session, history accumulates.
     *  - [[EvalSessionType.AllQuestionsPerSessionShuffled]]: one shared session with deterministically
     *    reordered using a fixed seed ([[ShuffleSeed]]).
     *
     *  @param questions        Questions to answer.
     *  @param mdRelPath        Relative path to the Markdown file.
     *  @param agent            Pre-built eval agent and its tool-run store.
     *  @param sweepDir         Sweep directory (used to create sessions).
     *  @param sessionType      Session mode.
     *  @param questionTimeoutMs Timeout for each QA agent turn.
     *  @param retryDelayMs      Optional fixed delay before retrying a timed-out turn.
     *  @return                  List of (question, TurnResult) pairs.
     */
    private def generateAnswers(
        questions:         List[EvalQuestion],
        mdRelPath:         String,
        agent:             EvalAgent,
        sweepDir:          Path,
        sessionType:       EvalSessionType,
        questionTimeoutMs: Long,
        retryDelayMs:      Option[Long]
    ): List[(EvalQuestion, TurnResult)] =
    {
        sessionType match
        {
            case EvalSessionType.OneQuestionPerSession =>
                questions.map { q =>
                    val t0 = System.currentTimeMillis()
                    log(s"  Q (isolated): ${q.question}")
                    val tr = answerQuestionWithRetry(q.question, mdRelPath, agent, makeSession(sweepDir),
                        Nil, questionTimeoutMs, retryDelayMs)
                    log(s"  A (isolated, elapsed=${System.currentTimeMillis() - t0}ms): ${tr.actual.take(200).replaceAll("\\s+", " ")}")
                    (q, tr)
                }

            case EvalSessionType.AllQuestionsPerSession =>
                generateAllQuestionsPerSession(questions, mdRelPath, agent, sweepDir, questionTimeoutMs, retryDelayMs)

            case EvalSessionType.AllQuestionsPerSessionShuffled =>
                val shuffled = new scala.util.Random(ShuffleSeed).shuffle(questions)
                log(s"  AllQuestionsPerSessionShuffled order (seed=$ShuffleSeed): ${shuffled.map(_.question.take(40)).mkString(" | ")}")
                generateAllQuestionsPerSession(shuffled, mdRelPath, agent, sweepDir, questionTimeoutMs, retryDelayMs)
        }
    }

    /**
     *  Runs all questions in a single session with history accumulation.
     *
     *  Shared by [[EvalSessionType.AllQuestionsPerSession]] and [[EvalSessionType.AllQuestionsPerSessionShuffled]].
     *
     *  @param questions        Questions to answer (in the desired order).
     *  @param mdRelPath        Relative path to the Markdown file.
     *  @param agent            Pre-built eval agent and its tool-run store.
     *  @param sweepDir         Sweep directory (used to create sessions).
     *  @param questionTimeoutMs Timeout for each QA agent turn.
     *  @param retryDelayMs      Optional fixed delay before retrying a timed-out turn.
     *  @return                  List of (question, TurnResult) pairs.
     */
    private def generateAllQuestionsPerSession(
        questions:         List[EvalQuestion],
        mdRelPath:         String,
        agent:             EvalAgent,
        sweepDir:          Path,
        questionTimeoutMs: Long,
        retryDelayMs:      Option[Long]
    ): List[(EvalQuestion, TurnResult)] =
    {
        val session = makeSession(sweepDir)
        val historyBuf = mutable.ListBuffer.empty[Message]
        questions.map { q =>
            val t0 = System.currentTimeMillis()
            log(s"  Q (shared): ${q.question}")
            val tr = answerQuestionWithRetry(q.question, mdRelPath, agent, session, historyBuf.toList,
                questionTimeoutMs, retryDelayMs)
            log(s"  A (shared, elapsed=${System.currentTimeMillis() - t0}ms): ${tr.actual.take(200).replaceAll("\\s+", " ")}")
            // Append user message and assistant reply to the conversation history.
            historyBuf += Message(
                id = s"hist-user-${historyBuf.size}", sessionId = session.id,
                role = MessageRole.User, content = q.question, timestamp = Instant.now().toString
            )
            historyBuf += Message(
                id = s"hist-asst-${historyBuf.size}", sessionId = session.id,
                role = MessageRole.Assistant, content = tr.actual, timestamp = Instant.now().toString
            )
            (q, tr)
        }
    }

    /**
     *  Wraps a blocking call in a Future with a hard timeout.
     *
     *  @param body       Code block to execute.
     *  @param timeoutMs  Timeout in milliseconds.
     *  @param label      Human-readable label for error messages.
     *  @return           Result of the body.
     */
    private def runWithTimeout[T](body: => T, timeoutMs: Long, label: String): T =
    {
        log(s"$label start timeout=${timeoutMs}ms")
        val t0 = System.currentTimeMillis()
        val future = evalExecutor.submit(new java.util.concurrent.Callable[T]
        {
            override def call(): T = body
        })
        try
        {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            log(s"$label complete elapsed=${System.currentTimeMillis() - t0}ms")
            result
        }
        catch
        {
            case _: TimeoutException =>
                log(s"$label TIMEOUT after ${timeoutMs}ms; cancelling worker")
                future.cancel(true)
                throw new TimeoutException(s"$label exceeded ${timeoutMs}ms")
            case t: Throwable =>
                log(s"$label ERROR ${t.getClass.getSimpleName}: ${t.getMessage}")
                future.cancel(true)
                throw t
        }
    }

    /**
     *  Calls the judge LLM to produce a short summary of zero-score failures,
     *  explaining why each occurred based on the judge rationales.
     *
     *  @param summaryText     Pre-formatted summary of scores, distributions, and
     *                         per-question rationales for zero-score answers.
     *  @param judgeProvider   LLM provider to use for generating the summary.
     *  @param timeoutMs       Maximum time to wait for the summary.
     *  @return                Summary string, or an error message on failure.
     */
    def generateCommentary(
        summaryText:   String,
        judgeProvider: LLMProvider,
        timeoutMs:     Long = 120000L
    ): String =
    {
        val prompt =
            s"""You are an expert QA evaluation analyst. Below are the aggregated results from an
               |end-to-end document question-answering evaluation. The system converts a PDF to
               |Markdown using a VLM, then answers questions using an LLM agent, and the answers
               |are judged with categorical verdicts: correct, partial, or wrong.
               |
               |$summaryText
               |
               |Write a concise analysis covering:
               |1. Which session types perform best/worst and why that might be.
               |2. Whether derived questions are harder than direct questions and what that implies.
               |3. Where the biggest quality gaps are (specific categories, session types, or verdict buckets).
               |4. A summary of every answer judged "wrong" — explain why it failed
               |   based on the judge rationale provided. Group by common patterns if applicable
               |   (e.g. hallucination, missing data, wrong entity).
               |5. Whether timeouts or other infrastructure failures skewed the results.
               |
               |If there are no wrong answers, briefly note that for point 4.
               |
               |Be specific and reference the numbers. Keep it under 400 words.
               |""".stripMargin

        val systemMsg = Message(
            id = "commentary-system", sessionId = "", role = MessageRole.System,
            content = "You are an expert evaluation analyst. Summarize failures factually.",
            timestamp = ""
        )
        val userMsg = Message(
            id = "commentary-user", sessionId = "", role = MessageRole.User,
            content = prompt, timestamp = ""
        )

        log(s"Commentary call start timeout=${timeoutMs}ms")
        val t0 = System.currentTimeMillis()
        val future = evalExecutor.submit(new java.util.concurrent.Callable[String]
        {
            override def call(): String =
            {
                val buf = new StringBuilder()
                judgeProvider.streamChatCompletions(List(systemMsg, userMsg), tok => buf.append(tok))
                buf.toString()
            }
        })

        try
        {
            val text = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            log(s"Commentary call complete elapsed=${System.currentTimeMillis() - t0}ms")
            text
        }
        catch
        {
            case _: TimeoutException =>
                log(s"Commentary call TIMEOUT after ${timeoutMs}ms; cancelling worker")
                future.cancel(true)
                "[Commentary generation timed out]"
            case t: Throwable =>
                log(s"Commentary call ERROR ${t.getClass.getSimpleName}: ${t.getMessage}")
                future.cancel(true)
                s"[Commentary generation failed: ${t.getClass.getSimpleName}: ${t.getMessage}]"
        }
    }

    // ─── Helpers on provider config ────────────────────────────────────────────────

    /**
     *  Builds the primary LLM provider for a config.
     */
    implicit class EvalProviderConfigOps(config: EvalProviderConfig)
    {
        def llmProvider: LLMProvider =
            OpenAIProvider(
                baseURL   = config.llmBaseURL,
                modelName = config.llmModel,
                apiKey    = config.llmAPIKey
            )

        def vlmProvider: Option[LLMProvider] =
            config.vlmBaseURL.flatMap { url =>
                config.vlmModel.map { model =>
                    OpenAIProvider(
                        baseURL   = url,
                        modelName = model,
                        apiKey    = config.vlmAPIKey.getOrElse("lm-studio")
                    )
                }
            }

        def judgeProvider: LLMProvider =
            config.judgeBaseURL.flatMap { url =>
                config.judgeModel.map { model =>
                    OpenAIProvider(
                        baseURL   = url,
                        modelName = model,
                        apiKey    = config.judgeAPIKey.getOrElse("lm-studio")
                    )
                }
            }.getOrElse(llmProvider)
    }

}
