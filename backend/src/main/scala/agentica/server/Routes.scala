package agentica.server

import agentica.agent.{AgentEngine, AgentEvent, ContextManager}
import agentica.observability.TraceLogger
import agentica.permissions.{GrantDecision, GrantTTL, ScopeStore}
import agentica.session.{MemoryStore, MessageRole, MessageStore, RunStore, Session, SessionStore}
import agentica.settings.{AppSettings, SettingsStore}
import agentica.shell.CommandRegistry
import cask.*
import upickle.default.*
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors, SynchronousQueue}
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/**
 *  All HTTP, static-file, and SSE routes for the Agentica backend.
 *  API routes validate bearer tokens before processing.
 *  @param sessionStore      Persistence layer for sessions.
 *  @param messageStore      Persistence layer for chat messages.
 *  @param runStore          Persistence layer for tool runs and token usage.
 *  @param settingsStore     JSON-backed application settings store.
 *  @param memoryStore       Session-scoped key-value memory store.
 *  @param scopeStore        Permission grant store for sensitive tools.
 *  @param commandRegistry   Registry of all registered tools; used for dispatch and help.
 *  @param agentEngine       Agent execution engine used to process user messages.
 *  @param uiRoot            Root directory containing static UI files.
 */
class Routes(
    sessionStore:    SessionStore,
    messageStore:    MessageStore,
    runStore:        RunStore,
    settingsStore:   SettingsStore,
    memoryStore:     MemoryStore,
    scopeStore:      ScopeStore,
    commandRegistry: CommandRegistry,
    agentEngine:     AgentEngine,
    uiRoot:          java.nio.file.Path
) extends MainRoutes
{

    // Maps runId → cancellation flag (set to true to request cancellation)
    private val cancelFlags      = ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicBoolean]()
    // Maps runId → SSE event queue (bounded, thread-safe)
    private val sseQueues        = ConcurrentHashMap[String, java.util.concurrent.LinkedBlockingQueue[String]]()
    // Maps runId → permission latch (rendez-vous with POST /permissions/:runId)
    private val permissionQueues = ConcurrentHashMap[String, SynchronousQueue[GrantDecision]]()

    private val virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor()

    private def mimeType(name: String): String = name match
    {
        case n if n.endsWith(".html") => "text/html; charset=utf-8"
        case n if n.endsWith(".css")  => "text/css; charset=utf-8"
        case n if n.endsWith(".js")   => "application/javascript; charset=utf-8"
        case n if n.endsWith(".png")  => "image/png"
        case n if n.endsWith(".ico")  => "image/x-icon"
        case n if n.endsWith(".ttf")  => "font/ttf"
        case _                        => "application/octet-stream"
    }

    private def serveFile(relativePath: String): Response[Response.Data] =
    {
        val path = uiRoot.resolve(relativePath).normalize()
        if !path.startsWith(uiRoot) then
            Response("Forbidden", statusCode = 403)
        else if !Files.exists(path) then
            Response("Not found", statusCode = 404)
        else
            Response(
                data    = Files.readAllBytes(path),
                headers = Seq("Content-Type" -> mimeType(path.getFileName.toString))
            )
    }

    // --- Static UI ---
    /** Redirects `/` to `/index.html`, preserving the optional query-token. */
    @cask.get("/")
    def root(token: String = "", request: Request): Response[Response.Data] =
        val loc = if token.nonEmpty then s"/index.html?token=$token" else "/index.html"
        Response("", statusCode = 302, headers = Seq("Location" -> loc))

    /** Serves the browser UI entrypoint. */
    @cask.get("/index.html")
    def indexHtml(token: String = "", request: Request): Response[Response.Data] =
        serveFile("index.html")

    /** Serves stylesheet assets from the UI root. */
    @cask.get("/css/:file")
    def cssFile(file: String, request: Request): Response[Response.Data] =
        serveFile(s"css/$file")

    /** Serves JavaScript assets from the UI root. */
    @cask.get("/js/:file")
    def jsFile(file: String, request: Request): Response[Response.Data] =
        serveFile(s"js/$file")

    /** Serves font assets from the UI root. */
    @cask.get("/fonts/:file")
    def fontFile(file: String, request: Request): Response[Response.Data] =
        serveFile(s"fonts/$file")

    private val corsHeaders: Seq[(String, String)] = Seq(
        "Access-Control-Allow-Origin"  -> "*",
        "Access-Control-Allow-Methods" -> "GET, POST, DELETE, OPTIONS",
        "Access-Control-Allow-Headers" -> "Authorization, Content-Type"
    )

    private def withCors(r: Response[Response.Data]): Response[Response.Data] =
        r.copy(headers = r.headers ++ corsHeaders)

    /** 
     *  Validates the bearer token on `request` and executes `body` if authorised.
     *  Short-circuits OPTIONS preflight requests with 204 + CORS headers.
     *  Returns a 401 JSON error response if the token is missing or invalid.
     */
    private def withAuth(request: Request)(body: => Response[Response.Data]): Response[Response.Data] =
    {
        if (request.exchange.getRequestMethod.toString == "OPTIONS") {
            Response("", statusCode = 204, headers = corsHeaders)
        } else {
            Auth.validate(request) match {
                case Left(err) => withCors(Response(s"""{"error":"$err"}""", statusCode = 401,
                                headers = Seq("Content-Type" -> "application/json")))
                case Right(_)  => withCors(body)
            }
        }
    }

    /** 
     *  Encodes a named SSE frame: `event: <event>\ndata: <data>\n\n`.
     *  Newlines inside `data` are escaped to keep each frame on a single data line.
     */
    private def sseEvent(event: String, data: String): String =
    {
        s"event: $event\ndata: ${data.replace("\n", "\\n")}\n\n"
    }

    // --- Settings ---
    /** 
     *  Returns persisted application settings.
     */
    @cask.get("/settings")
    def getSettings(request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            Response(write(settingsStore.load()), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    /** 
     *  Saves application settings and returns the normalized persisted value.
     */
    @cask.route("/settings", methods = Seq("post", "options"))
    def saveSettings(request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val body = read[AppSettings](request.text())
            Response(write(settingsStore.save(body)), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    // --- Health ---
    /** 
     *  Returns `{"status":"ok"}` for launchers and smoke checks.
     */
    @cask.route("/health", methods = Seq("get", "options"))
    def health(request: Request): Response[Response.Data] =
    {
        if (request.exchange.getRequestMethod.toString == "OPTIONS") {
            Response("", statusCode = 204, headers = corsHeaders)
        } else {
            withCors(Response("""{"status":"ok"}""", headers = Seq("Content-Type" -> "application/json")))
        }
    }

    // --- Sessions ---
    /** 
     *  Lists all sessions ordered by most-recently-updated first.
     */
    @cask.get("/sessions")
    def listSessions(request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val sessions = sessionStore.list()
            Response(write(sessions), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    /** 
     *  Creates a new session from a JSON body `{title, model, rootPath?}`. Returns 201 with the created session.
     */
    @cask.route("/sessions", methods = Seq("post", "options"))
    def createSession(request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val body     = ujson.read(request.text())
            val title    = body.obj.get("title").map(_.str).getOrElse("New Session")
            val model    = body.obj.get("model").map(_.str).getOrElse("llama3.2")
            val rootPath = body.obj.get("rootPath").flatMap(v => if v.isNull then None else Some(v.str))
            val session  = sessionStore.create(title, model, rootPath)
            TraceLogger.info("-", "session_created", Map("sessionId" -> session.id))
            Response(write(session), statusCode = 201, headers = Seq("Content-Type" -> "application/json"))
        }
    }

    /** 
     *  Returns the session with the given `id`, or 404 if not found.
     */
    @cask.get("/sessions/:id")
    def getSession(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            sessionStore.get(id) match
            {
                case Some(s) =>
                    Response(write(s), headers = Seq("Content-Type" -> "application/json"))
                case None    =>
                    Response("""{"error":"not found"}""", statusCode = 404,
                                headers = Seq("Content-Type" -> "application/json"))
            }
        }
    }

    /** 
     *  Deletes a session and all its messages. Returns 204 on success.
     */
    @cask.route("/sessions/:id", methods = Seq("delete", "options"))
    def deleteSession(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            messageStore.deleteForSession(id)
            sessionStore.delete(id)
            Response("", statusCode = 204)
        }
    }

    // --- Messages ---
    /** 
     *  Returns all messages for session `id` in chronological order.
     */
    @cask.get("/sessions/:id/messages")
    def listMessages(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val messages = messageStore.listForSession(id)
            Response(write(messages), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    /** 
     *  Appends a user message and starts an agent run on a virtual thread.
     *  Returns 202 immediately with `{runId, traceId, userMessageId}`.
     *  The caller polls the SSE stream at `/sessions/:id/stream/:runId` for events.
     */
    @cask.route("/sessions/:id/messages", methods = Seq("post", "options"))
    def postMessage(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            sessionStore.get(id) match
            {
                case None =>
                    Response("""{"error":"session not found"}""", statusCode = 404,
                        headers = Seq("Content-Type" -> "application/json"))
                case Some(session) =>
                    val body      = ujson.read(request.text())
                    val content   = body("content").str
                    val traceId   = UUID.randomUUID().toString
                    val runId     = UUID.randomUUID().toString
                    val userMsg   = messageStore.append(id, MessageRole.User, content)
                    val history   = messageStore.listForSession(id).dropRight(1)
                    val cancel          = java.util.concurrent.atomic.AtomicBoolean(false)
                    val queue           = java.util.concurrent.LinkedBlockingQueue[String](1024)
                    val permLatch       = SynchronousQueue[GrantDecision]()
                    cancelFlags.put(runId, cancel)
                    sseQueues.put(runId, queue)
                    permissionQueues.put(runId, permLatch)
                    TraceLogger.info(traceId, "run_start", Map("sessionId" -> id, "runId" -> runId))
                    virtualThreadPool.submit(new Runnable
                    {
                        def run(): Unit =
                        {
                            try
                            {
                                agentEngine.run(
                                    session    = session,
                                    history    = history,
                                    userMsg    = userMsg,
                                    traceId    = traceId,
                                    cancelFlag = cancel,
                                    emitToken  = tok => if !cancel.get() then queue.offer(sseEvent("token", tok)),
                                    emitEvent  = ev =>
                                    {
                                        val eventStr = ev match
                                        {
                                            case AgentEvent.IterationBoundary(i) =>
                                                sseEvent("iteration", s"""{"iteration":$i}""")
                                            case AgentEvent.LLMCallStart(i, m, n) =>
                                                val mJson = ujson.Str(m).render()
                                                sseEvent("llm_call_start", s"""{"iteration":$i,"model":$mJson,"msgCount":$n}""")
                                            case AgentEvent.ToolCallStart(t, inp) =>
                                                val tJson   = ujson.Str(t).render()
                                                val inpJson = if inp.isEmpty then "{}" else inp
                                                sseEvent("tool_start", s"""{"tool":$tJson,"input":$inpJson}""")
                                            case AgentEvent.ToolCallResult(t, out, ms) =>
                                                val tJson   = ujson.Str(t).render()
                                                val outJson = ujson.Str(out).render()
                                                sseEvent("tool_result", s"""{"tool":$tJson,"output":$outJson,"durationMs":$ms}""")
                                            case AgentEvent.Final(msgId) =>
                                                sseEvent("final", s"""{"messageId":"$msgId"}""")
                                            case AgentEvent.Cancelled =>
                                                sseEvent("cancelled", "{}")
                                            case AgentEvent.AgentError(msg) =>
                                                val msgJson = ujson.Str(msg).render()
                                                sseEvent("error", s"""{"message":$msgJson}""")
                                            case AgentEvent.PermissionRequired(tool, path, opts) =>
                                                val toolJson = ujson.Str(tool).render()
                                                val pathStr  = path.map(p => ",\"path\":" + ujson.Str(p).render()).getOrElse("")
                                                val optsJson = opts.map(o => ujson.Str(o).render()).mkString("[", ",", "]")
                                                sseEvent("permission_required", s"""{"tool":$toolJson$pathStr,"options":$optsJson,"runId":"$runId"}""")
                                        }
                                        queue.offer(eventStr)
                                        ev match
                                        {
                                            case AgentEvent.Final(_) | AgentEvent.Cancelled | AgentEvent.AgentError(_) =>
                                                queue.offer(sseEvent("done", "{}"))
                                            case AgentEvent.PermissionRequired(_, _, _) => ()
                                            case _ => ()
                                        }
                                    }
                                )
                            }
                            catch
                            {
                                case ex: Exception =>
                                    TraceLogger.error(traceId, "run_error", Map("error" -> ex.getMessage))
                                    queue.offer(sseEvent("error", s"""{"message":${ujson.Str(ex.getMessage).render()}}"""))
                                    queue.offer(sseEvent("done", "{}"))
                            }
                            finally
                            {
                                cancelFlags.remove(runId)
                            }
                        }
                    })
                    Response(
                        write(ujson.Obj("runId" -> runId, "traceId" -> traceId, "userMessageId" -> userMsg.id)),
                        statusCode = 202,
                        headers    = Seq("Content-Type" -> "application/json")
                    )
            }
        }
    }

    // --- SSE stream ---
    /** 
     * Opens an SSE stream for the given `runId`.
     *  Blocks (on a virtual thread) on the run's event queue and emits each event as it arrives.
     *  Closes the stream when a `done`, `final`, or `cancelled` frame is dequeued.
     */
    @cask.route("/sessions/:id/stream/:runId", methods = Seq("get", "options"))
    def streamRun(id: String, runId: String, request: Request): Response[Response.Data] =
    {
        if (request.exchange.getRequestMethod.toString == "OPTIONS")
        {
            Response("", statusCode = 204, headers = corsHeaders)
        }
        else Auth.validate(request) match
        {
            case Left(err) =>
                Response(s"""{"error":"$err"}""", statusCode = 401,
                    headers = Seq("Content-Type" -> "application/json"))
            case Right(_) =>
                val queue = sseQueues.get(runId)
                if (queue == null)
                {
                    Response("""{"error":"run not found"}""", statusCode = 404,
                        headers = Seq("Content-Type" -> "application/json"))
                }
                else
                {
                    val writable: geny.Writable = (out: java.io.OutputStream) =>
                    {
                        var done = false
                        while !done do
                        {
                            val ev = queue.take()  // blocks on virtual thread — safe with Loom
                            out.write(ev.getBytes("UTF-8"))
                            out.flush()
                            if (ev.contains("event: done") || ev.contains("event: final") || ev.contains("event: cancelled"))
                            {
                                done = true
                            }
                        }
                        sseQueues.remove(runId)
                    }
                    withCors(Response(
                        writable,
                        headers = Seq(
                            "Content-Type"      -> "text/event-stream",
                            "Cache-Control"     -> "no-cache",
                            "Connection"        -> "keep-alive",
                            "X-Accel-Buffering" -> "no"
                        )
                    ))
                }
        }
    }

    // --- Cancellation ---

    /** 
     *  Sets the cancellation flag for the given `runId`; the agent loop checks it between tokens.
     *  Returns 204 regardless of whether the run exists.
     */
    @cask.route("/runs/:runId", methods = Seq("delete", "options"))
    def cancelRun(runId: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val flag = cancelFlags.get(runId)
            if (flag != null)
            {
                flag.set(true)
            }
            Response("", statusCode = 204)
        }
    }

    // --- Permissions ---

    /**
     *  Receives a permission decision from the UI modal and unblocks the suspended agent run.
     *  Request body JSON: `{"decision":"granted"|"denied", "ttl":"Once"|"ForSession"|"Always", "pathPrefix":"..."|null}`.
     *  Returns 204 on success, 404 if the run is not awaiting a decision.
     */
    @cask.route("/permissions/:runId", methods = Seq("post", "options"))
    def resolvePermission(runId: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val latch = permissionQueues.get(runId)
            if (latch == null)
            {
                Response("""{"error":"run not found or not awaiting permission"}""", statusCode = 404,
                    headers = Seq("Content-Type" -> "application/json"))
            }
            else
            {
                val body       = ujson.read(request.text())
                val decisionStr = body.obj.get("decision").map(_.str).getOrElse("denied")
                val decision = decisionStr match
                {
                    case "granted" =>
                        val ttlStr = body.obj.get("ttl").map(_.str).getOrElse("Once")
                        val ttl = ttlStr match
                        {
                            case "ForSession" => GrantTTL.ForSession
                            case "Always"     => GrantTTL.Always
                            case _            => GrantTTL.Once
                        }
                        val pathPrefix = body.obj.get("pathPrefix").flatMap(v =>
                            if (v.isNull) None else Some(v.str)
                        )
                        GrantDecision.Granted(ttl = ttl, pathPrefix = pathPrefix)
                    case _ =>
                        GrantDecision.Denied
                }
                latch.offer(decision)
                permissionQueues.remove(runId)
                Response("", statusCode = 204)
            }
        }
    }

    // --- Token usage ---

    /**
     *  Returns all token-usage records for session `id` in chronological order.
     */
    @cask.route("/sessions/:id/token-usage", methods = Seq("get", "options"))
    def tokenUsage(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val usage = runStore.tokenUsageForSession(id)
            Response(write(usage), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    // --- Log streaming ---

    /**
     *  WebSocket endpoint for streaming log lines.
     *  On connect: replays last 200 lines of agentica.log, then tails the file
     *  at 100ms intervals until the client disconnects.
     *  Authentication is provided via the `token` query parameter.
     *  @param token   Auth token passed as a query parameter.
     *  @param request Incoming HTTP upgrade request.
     *  @return        A [[cask.WsHandler]] that drives the WebSocket lifecycle.
     */
    @cask.websocket("/log/stream")
    def logStream(token: String = "", request: Request): cask.WsHandler =
    {
        Auth.validateOrQueryToken(request, token) match
        {
            case Left(_) =>
                cask.WsHandler { channel =>
                    channel.send(cask.Ws.Text("""{"error":"unauthorized"}"""))
                    channel.send(cask.Ws.Close())
                    cask.WsActor { case _ => () }
                }
            case Right(_) =>
                cask.WsHandler { channel =>
                    val logFile  = agentica.platform.AppDirs.logFile
                    var lastPos  = 0L

                    // Replay last 200 lines
                    val replayLines = if (Files.exists(logFile)) then
                    {
                        val allLines = Files.readAllLines(logFile).asScala
                        lastPos = Files.size(logFile)
                        allLines.takeRight(200)
                    }
                    else
                    {
                        Seq.empty
                    }

                    replayLines.foreach { line =>
                        channel.send(cask.Ws.Text(line))
                    }

                    // Tail the log file on a daemon thread until the channel closes
                    val tailThread = new Thread(() =>
                    {
                        try
                        {
                            while (!Thread.currentThread().isInterrupted())
                            {
                                Thread.sleep(100)
                                if (Files.exists(logFile))
                                {
                                    val currentSize = Files.size(logFile)
                                    if (currentSize > lastPos)
                                    {
                                        val raf = new java.io.RandomAccessFile(logFile.toFile, "r")
                                        try
                                        {
                                            raf.seek(lastPos)
                                            var line = raf.readLine()
                                            while (line != null)
                                            {
                                                val utf8Line = new String(
                                                    line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                                                    java.nio.charset.StandardCharsets.UTF_8
                                                )
                                                channel.send(cask.Ws.Text(utf8Line))
                                                line = raf.readLine()
                                            }
                                            lastPos = raf.getFilePointer()
                                        }
                                        finally
                                        {
                                            raf.close()
                                        }
                                    }
                                    else if (currentSize < lastPos)
                                    {
                                        lastPos = 0L
                                    }
                                }
                            }
                        }
                        catch
                        {
                            case _: InterruptedException => ()
                        }
                    })
                    tailThread.setDaemon(true)
                    tailThread.start()

                    cask.WsActor(
                    {
                        case cask.Ws.Close(_, _)  => tailThread.interrupt()
                        case cask.Ws.ChannelClosed => tailThread.interrupt()
                        case _: cask.Ws.Event      => ()
                    }: PartialFunction[cask.Ws.Event, Unit]
                    )
                }
        }
    }

    // --- Static files for log viewer ---

    /**
     *  Serves the log-viewer.html page.
     */
    @cask.route("/log-viewer.html", methods = Seq("get"))
    def logViewerPage(token: String = "", request: Request): Response[Response.Data] =
    {
        val file = uiRoot.resolve("log-viewer.html")
        if (Files.exists(file)) then
        {
            Response(Files.readString(file), headers = Seq("Content-Type" -> "text/html"))
        }
        else
        {
            Response("<h1>Log viewer not found</h1>", statusCode = 404)
        }
    }

    initialize()
}
