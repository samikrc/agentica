package agentica.testutil

import agentica.agent.{AgentEvent, AgentLoop, ContextManager}
import agentica.doc.{PDFPageRenderer, PageVisionTranscriber}
import agentica.llm.{LLMProvider, LLMResponse, OpenAIProvider}
import agentica.misctests.{MarkdownScore, MarkdownScorer}
import agentica.observability.TokenAccounting
import agentica.permissions.{GrantDecision, GrantTTL, PermissionCoordinator, ScopeStore}
import agentica.session.{AgentTurn, AgentTurnStore, MemoryEntry, MemoryStore, Message, MessageRole, MessageStore, RunStatus, RunStore, Session, SessionStore, ToolRun}
import agentica.settings.AppSettings
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.tools.{AgentResponse, ExecutionContext, ToolBody}
import agentica.tools.files.{FilesList, FilesRead, FilesReadDOCXToMarkdown, FilesReadPDFToMarkdown, FilesReadPDFToMarkdownInput, FilesReadPPTXToMarkdown, FilesSearch, FilesStat, FilesWrite}
import agentica.tools.memory.{MemoryGet, MemoryList, MemorySet}
import ujson.*
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicBoolean
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
    sessionTypes:         List[EvalSessionType]   = List(EvalSessionType.AllQuestionsPerSession)
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
     *  Convenience factory for a single OpenAI-compatible endpoint with an explicit label.
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
                sessionTypes         = sessionTypes
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
 *  Result of judging one predicted answer.
 *
 *  @param question         Original question.
 *  @param category         Question category ("direct" or "rephrase").
 *  @param referenceAnswer  Ground-truth answer from the eval JSON.
 *  @param actualAnswer     Final answer produced by the agent (last iteration only).
 *  @param thoughts         All reasoning and intermediate text from earlier iterations.
 *  @param toolCalls        Ordered list of raw tool call commands issued by the agent.
 *  @param judgeScore       Numeric score from 0.0 to 1.0.
 *  @param judgeRationale   Short rationale produced by the judge LLM.
 *  @param toolCounts       Map of tool name -> number of calls while answering this question.
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
    toolCounts:       Map[String, Int]
)

/**
 *  Aggregated result for one provider run against one document.
 *
 *  @param providerLabel  Label of the evaluated provider config.
 *  @param sessionType    Session allocation strategy label.
 *  @param markdownPath   Path to the generated Markdown file.
 *  @param markdownScore  MarkdownScorer output for the generated Markdown.
 *  @param answers        List of judged question/answer results.
 */
case class EvalResult(
    providerLabel: String,
    sessionType:   String,
    markdownPath:  Path,
    markdownScore: MarkdownScore,
    answers:       List[AnswerResult]
)

/**
 *  Reusable harness for end-to-end document QA evaluation.
 *
 *  Given a PDF, a JSON question set, and a list of (LLM, VLM) configurations, the harness:
 *    1. Copies the PDF into a temporary workspace.
 *    2. Generates Markdown via the real `files.read_pdf_to_markdown` tool path.
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

    private def ts(): String = java.time.Instant.now().toString
    private def log(msg: String): Unit = println(s"[${ts()}] $msg")

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
            enrichImages = true
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
     *  @return                Tuple of (actual answer, thoughts, tool calls, tool call counts).
     */
    private def answerQuestion(
        question:        String,
        markdownRelPath: String,
        agent:           EvalAgent,
        session:         Session,
        history:         List[Message] = Nil,
        timeoutMs:       Long = 120000L
    ): (String, String, List[String], Map[String, Int]) =
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
     *  @return           Tuple of (actual answer, thoughts, tool calls, tool call counts).
     */
    private def runAgentTurn(
        agent:     EvalAgent,
        session:   Session,
        history:   List[Message],
        userMsg:   Message,
        timeoutMs: Long
    ): (String, String, List[String], Map[String, Int]) =
    {
        val cancelFlag = new AtomicBoolean(false)
        val traceId    = s"eval-qa-${UUID.randomUUID()}"
        val permissionCoordinator = new PermissionCoordinator(traceId)
        val answerBuf  = new StringBuilder()
        val lastLLMStartPos = new java.util.concurrent.atomic.AtomicInteger(0)

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
            (actual, thoughts, toolCalls, toolCounts)
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
                (actual, thoughts, toolCalls, toolCounts)
            case t: Throwable =>
                log(s"Agent turn ERROR traceId=$traceId ${t.getClass.getSimpleName}: ${t.getMessage}")
                future.cancel(true)
                permissionCoordinator.close()
                val (toolCalls, toolCounts) = capturedTools
                (s"[Agent turn error: ${t.getClass.getSimpleName}: ${t.getMessage}]", "", toolCalls, toolCounts)
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
     *  @return                   Tuple of (score, rationale).
     */
    def judgeAnswer(
        question:        String,
        referenceAnswer: String,
        predictedAnswer: String,
        judgeProvider:   LLMProvider,
        timeoutMs:       Long = 60000L
    ): (Double, String) =
    {
        val prompt =
            s"""You are an expert evaluator. Compare the predicted answer to the reference answer for the given question.
               |Score how factually correct and complete the predicted answer is, based only on the reference.
               |
               |Question: $question
               |Reference Answer: $referenceAnswer
               |Predicted Answer: $predictedAnswer
               |
               |Return ONLY a JSON object in this exact format:
               |{"score": 0.0, "rationale": "Brief explanation"}
               |
               |Score scale:
               |- 1.0: fully correct and complete
               |- 0.5: partially correct or missing important details
               |- 0.0: incorrect, unsupported, or hallucinated
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

        log(s"Judge call start timeout=${timeoutMs}ms")
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
            val responseText = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            log(s"Judge call complete elapsed=${System.currentTimeMillis() - t0}ms")
            parseJudgeResponse(responseText)
        }
        catch
        {
            case _: TimeoutException =>
                log(s"Judge call TIMEOUT after ${timeoutMs}ms; cancelling worker")
                future.cancel(true)
                (0.0, "Judge call timed out")
            case t: Throwable =>
                log(s"Judge call ERROR ${t.getClass.getSimpleName}: ${t.getMessage}")
                future.cancel(true)
                (0.0, s"Judge error: ${t.getClass.getSimpleName}: ${t.getMessage}")
        }
    }

    /**
     *  Parses the judge JSON response into (score, rationale).
     *  Falls back to (0.0, "Unable to parse judge response") on failure.
     */
    private def parseJudgeResponse(text: String): (Double, String) =
    {
        try
        {
            // Find the first '{' ... '}' substring to tolerate surrounding text.
            val jsonMatch = raw"\{.*\}".r.findFirstIn(text.replaceAll("\\s+", " "))
            jsonMatch match
            {
                case Some(jsonStr) =>
                    val json = ujson.read(jsonStr)
                    val score = json.obj.get("score").map(_.num).getOrElse(0.0)
                    val rationale = json.obj.get("rationale").map(_.str).getOrElse("No rationale provided")
                    (score, rationale)
                case None =>
                    (0.0, s"No JSON object found in judge response: $text")
            }
        }
        catch
        {
            case t: Throwable =>
                (0.0, s"Failed to parse judge response: ${t.getMessage}. Raw: $text")
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
                "judgeRationale"  -> Str(a.judgeRationale)
            )
        }*)
        Files.writeString(sweepDir.resolve("questions.json"), json.render(indent = 2))
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
        sweepDir:      Path,
        fileStem:      String,
        providerLabel: String,
        mdScore:       MarkdownScore,
        answers:       List[AnswerResult]
    ): Unit =
    {
        val avgScore = if (answers.isEmpty) 0.0 else answers.map(_.judgeScore).sum / answers.length
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
            "totalToolCounts"   -> Obj.from(totalToolCounts.map { case (k, v) => k -> Num(v) }),
            "questionsFile"     -> Str("questions.json")
        )
        Files.writeString(sweepDir.resolve("summary.json"), json.render(indent = 2))
    }

    /** Default pause (ms) between answer generation and judging when the judge endpoint is local. */
    private val DefaultLocalPauseMs: Long = 30000L

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
     */
    case class EvalProviderBundle(
        label:                String,
        llm:                  LLMProvider,
        vlm:                  Option[LLMProvider],
        judge:                LLMProvider,
        sessionTypes:         List[EvalSessionType] = List(EvalSessionType.AllQuestionsPerSession),
        pauseBetweenPhasesMs: Option[Long]            = None,
        beforeJudge:          () => Unit               = () => ()
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
     *  @return                  Tuple of (root work directory, list of EvalResult).
     */
    def runEvalBundles(
        pdfPath:           Path,
        questions:         List[EvalQuestion],
        bundles:           List[EvalProviderBundle],
        settings:          AppSettings,
        pdfTimeoutMs:      Long = 600000L,
        questionTimeoutMs: Long = 120000L
    ): (Path, List[EvalResult]) =
    {
        ensureToolIndexApplied()

        val rootWorkDir = Files.createTempDirectory("pdf-eval-")
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

            try
            {
                val llm = bundle.llm
                val vlm = bundle.vlm
                val agent = makeAgentLoop(llm, vlm, settings)

                // Convert PDF to Markdown once per provider (VLM-intensive).
                val workPDF = providerDir.resolve(pdfName)
                Files.copy(fileDir.resolve(pdfName), workPDF, StandardCopyOption.REPLACE_EXISTING)

                log(s"--- Converting PDF to Markdown: ${bundle.label} ---")
                val conversionSession = makeSession(providerDir)
                val (markdown, mdRelPath) = runWithTimeout(
                    convertPDFToMarkdown(workPDF, conversionSession, llm, vlm, settings),
                    pdfTimeoutMs,
                    "PDF conversion"
                )
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
                    val qaPairs = generateAnswers(questions, mdRelPath, agent, sweepDir, sessionType, questionTimeoutMs)

                    (sessionType, sweepDir, mdSavePath, mdScore, qaPairs)
                }

                // Phase 2: provider-specific pre-judge hook (e.g. LM-Studio model swap).
                bundle.beforeJudge()
                bundle.pauseBetweenPhasesMs.foreach { ms =>
                    log(s"Pausing ${ms}ms before judging...")
                    Thread.sleep(ms)
                }

                // Phase 3: judge all answers across all session types.
                phaseOneResults.flatMap { case (sessionType, sweepDir, mdSavePath, mdScore, qaPairs) =>
                    try
                    {
                        log(s"--- Judging: ${bundle.label} / ${sessionType.label} (${qaPairs.length} answers) ---")
                        val answerResults = qaPairs.map { case (q, actual, thoughts, toolCalls, toolCounts) =>
                            val (score, rationale) = judgeAnswer(q.question, q.referenceAnswer, actual, bundle.judge, questionTimeoutMs)
                            println(f"    Score: $score%.2f — $rationale")
                            AnswerResult(q.question, q.category, q.referenceAnswer, actual, thoughts, toolCalls, score, rationale, toolCounts)
                        }

                        saveQuestions(sweepDir, answerResults)
                        saveSummary(sweepDir, fileStem, bundle.label, mdScore, answerResults)

                        Some(EvalResult(bundle.label, sessionType.label, mdSavePath, mdScore, answerResults))
                    }
                    catch
                    {
                        case t: Throwable =>
                            log(s"ERROR judging ${bundle.label}/${sessionType.label}: ${t.getClass.getSimpleName}: ${t.getMessage}")
                            None
                    }
                }
            }
            catch
            {
                case t: Throwable =>
                    log(s"ERROR for ${bundle.label}: ${t.getClass.getSimpleName}: ${t.getMessage}")
                    Nil
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
     *  @return                 List of (question, actual answer, thoughts, tool calls, tool counts).
     */
    private def generateAnswers(
        questions:         List[EvalQuestion],
        mdRelPath:         String,
        agent:             EvalAgent,
        sweepDir:          Path,
        sessionType:       EvalSessionType,
        questionTimeoutMs: Long
    ): List[(EvalQuestion, String, String, List[String], Map[String, Int])] =
    {
        sessionType match
        {
            case EvalSessionType.OneQuestionPerSession =>
                questions.map { q =>
                    val t0 = System.currentTimeMillis()
                    log(s"  Q (isolated): ${q.question}")
                    val (actual, thoughts, toolCalls, toolCounts) =
                        answerQuestion(q.question, mdRelPath, agent, makeSession(sweepDir), timeoutMs = questionTimeoutMs)
                    log(s"  A (isolated, elapsed=${System.currentTimeMillis() - t0}ms): ${actual.take(200).replaceAll("\\s+", " ")}")
                    (q, actual, thoughts, toolCalls, toolCounts)
                }

            case EvalSessionType.AllQuestionsPerSession =>
                generateAllQuestionsPerSession(questions, mdRelPath, agent, sweepDir, questionTimeoutMs)

            case EvalSessionType.AllQuestionsPerSessionShuffled =>
                val shuffled = new scala.util.Random(ShuffleSeed).shuffle(questions)
                log(s"  AllQuestionsPerSessionShuffled order (seed=$ShuffleSeed): ${shuffled.map(_.question.take(40)).mkString(" | ")}")
                generateAllQuestionsPerSession(shuffled, mdRelPath, agent, sweepDir, questionTimeoutMs)
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
     *  @return                 List of (question, actual answer, thoughts, tool calls, tool counts).
     */
    private def generateAllQuestionsPerSession(
        questions:         List[EvalQuestion],
        mdRelPath:         String,
        agent:             EvalAgent,
        sweepDir:          Path,
        questionTimeoutMs: Long
    ): List[(EvalQuestion, String, String, List[String], Map[String, Int])] =
    {
        val session = makeSession(sweepDir)
        val historyBuf = mutable.ListBuffer.empty[Message]
        questions.map { q =>
            val t0 = System.currentTimeMillis()
            log(s"  Q (shared): ${q.question}")
            val (actual, thoughts, toolCalls, toolCounts) =
                answerQuestion(q.question, mdRelPath, agent, session, history = historyBuf.toList, timeoutMs = questionTimeoutMs)
            log(s"  A (shared, elapsed=${System.currentTimeMillis() - t0}ms): ${actual.take(200).replaceAll("\\s+", " ")}")
            // Append user message and assistant reply to the conversation history.
            historyBuf += Message(
                id = s"hist-user-${historyBuf.size}", sessionId = session.id,
                role = MessageRole.User, content = q.question, timestamp = Instant.now().toString
            )
            historyBuf += Message(
                id = s"hist-asst-${historyBuf.size}", sessionId = session.id,
                role = MessageRole.Assistant, content = actual, timestamp = Instant.now().toString
            )
            (q, actual, thoughts, toolCalls, toolCounts)
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
               |are judged on a scale of 0.0, 0.5, or 1.0.
               |
               |$summaryText
               |
               |Write a concise analysis covering:
               |1. Which session types perform best/worst and why that might be.
               |2. Whether derived questions are harder than direct questions and what that implies.
               |3. Where the biggest quality gaps are (specific categories, session types, or score buckets).
               |4. A summary of every answer that received a score of 0.0 — explain why it failed
               |   based on the judge rationale provided. Group by common patterns if applicable
               |   (e.g. hallucination, missing data, wrong entity).
               |
               |If there are no zero-score answers, briefly note that for point 4.
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
