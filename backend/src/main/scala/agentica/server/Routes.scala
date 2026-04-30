package agentica.server

import agentica.agent.{AgentEngine, AgentEvent, ContextManager}
import agentica.observability.TraceLogger
import agentica.session.{MessageStore, RunStore, Session, SessionStore}
import cask.*
import upickle.default.*
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors}
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// All HTTP + SSE routes for the Agentica backend.
// Every route validates the bearer token before processing.
//
// Routes:
//   GET    /                              serves ui/index.html (redirect)
//   GET    /index.html                    serves ui/index.html
//   GET    /css/:file                     serves ui/css/:file
//   GET    /js/:file                      serves ui/js/:file
//   GET    /health
//   GET    /sessions
//   POST   /sessions
//   GET    /sessions/:id
//   DELETE /sessions/:id
//   GET    /sessions/:id/messages
//   POST   /sessions/:id/messages         starts agent run, returns runId
//   GET    /sessions/:id/stream/:runId    SSE stream of tokens + events
//   DELETE /runs/:runId                   cancel in-flight run
//   GET    /sessions/:id/token-usage
class Routes(
    sessionStore: SessionStore,
    messageStore: MessageStore,
    runStore:     RunStore,
    agentEngine:  AgentEngine,
    uiRoot:       java.nio.file.Path
) extends MainRoutes
{

    // Maps runId → cancellation flag (set to true to request cancellation)
    private val cancelFlags = ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicBoolean]()
    // Maps runId → SSE event queue (bounded, thread-safe)
    private val sseQueues   = ConcurrentHashMap[String, java.util.concurrent.LinkedBlockingQueue[String]]()

    private val virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor()

    private def mimeType(name: String): String = name match
    {
        case n if n.endsWith(".html") => "text/html; charset=utf-8"
        case n if n.endsWith(".css")  => "text/css; charset=utf-8"
        case n if n.endsWith(".js")   => "application/javascript; charset=utf-8"
        case n if n.endsWith(".png")  => "image/png"
        case n if n.endsWith(".ico")  => "image/x-icon"
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
    @cask.get("/")
    def root(token: String = "", request: Request): Response[Response.Data] =
        val loc = if token.nonEmpty then s"/index.html?token=$token" else "/index.html"
        Response("", statusCode = 302, headers = Seq("Location" -> loc))

    @cask.get("/index.html")
    def indexHtml(token: String = "", request: Request): Response[Response.Data] =
        serveFile("index.html")

    @cask.get("/css/:file")
    def cssFile(file: String, request: Request): Response[Response.Data] =
        serveFile(s"css/$file")

    @cask.get("/js/:file")
    def jsFile(file: String, request: Request): Response[Response.Data] =
        serveFile(s"js/$file")

    private val corsHeaders: Seq[(String, String)] = Seq(
        "Access-Control-Allow-Origin"  -> "*",
        "Access-Control-Allow-Methods" -> "GET, POST, DELETE, OPTIONS",
        "Access-Control-Allow-Headers" -> "Authorization, Content-Type"
    )

    private def withCors(r: Response[Response.Data]): Response[Response.Data] =
        r.copy(headers = r.headers ++ corsHeaders)

    /** Validates the bearer token on `request` and executes `body` if authorised.
     *  Short-circuits OPTIONS preflight requests with 204 + CORS headers.
     *  Returns a 401 JSON error response if the token is missing or invalid.
     */
    private def withAuth(request: Request)(body: => Response[Response.Data]): Response[Response.Data] =
    {
        if request.exchange.getRequestMethod.toString == "OPTIONS" then
            Response("", statusCode = 204, headers = corsHeaders)
        else Auth.validate(request) match
        {
            case Left(err) => withCors(Response(s"""{"error":"$err"}""", statusCode = 401,
                                headers = Seq("Content-Type" -> "application/json")))
            case Right(_)  => withCors(body)
        }
    }

    /** Encodes a named SSE frame: `event: <event>\ndata: <data>\n\n`.
     *  Newlines inside `data` are escaped to keep each frame on a single data line.
     */
    private def sseEvent(event: String, data: String): String =
    {
        s"event: $event\ndata: ${data.replace("\n", "\\n")}\n\n"
    }

    // --- Health ---
    /** Returns `{"status":"ok"}` for launchers and smoke checks. */
    @cask.route("/health", methods = Seq("get", "options"))
    def health(request: Request): Response[Response.Data] =
    {
        if request.exchange.getRequestMethod.toString == "OPTIONS" then
            Response("", statusCode = 204, headers = corsHeaders)
        else
            withCors(Response("""{"status":"ok"}""", headers = Seq("Content-Type" -> "application/json")))
    }

    // --- Sessions ---
    /** Lists all sessions ordered by most-recently-updated first. */
    @cask.get("/sessions")
    def listSessions(request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val sessions = sessionStore.list()
            Response(write(sessions), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    /** Creates a new session from a JSON body `{title, model, rootPath?}`. Returns 201 with the created session. */
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

    /** Returns the session with the given `id`, or 404 if not found. */
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

    /** Deletes a session and all its messages. Returns 204 on success. */
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
    /** Returns all messages for session `id` in chronological order. */
    @cask.get("/sessions/:id/messages")
    def listMessages(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val messages = messageStore.listForSession(id)
            Response(write(messages), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    /** Appends a user message and starts an agent run on a virtual thread.
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
                    val userMsg   = messageStore.append(id, "user", content)
                    val history   = messageStore.listForSession(id)
                    val assembled = ContextManager.assemble(history.dropRight(1))
                    val cancel    = java.util.concurrent.atomic.AtomicBoolean(false)
                    val queue     = java.util.concurrent.LinkedBlockingQueue[String](1024)
                    cancelFlags.put(runId, cancel)
                    sseQueues.put(runId, queue)
                    TraceLogger.info(traceId, "run_start", Map("sessionId" -> id, "runId" -> runId))
                    virtualThreadPool.submit(new Runnable
                    {
                        def run(): Unit =
                        {
                            try
                            {
                                agentEngine.run(
                                    session = session,
                                    history = assembled,
                                    userMsg = userMsg,
                                    traceId = traceId,
                                    onToken = tok => if !cancel.get() then queue.offer(sseEvent("token", tok)),
                                    onEvent = ev =>
                                    {
                                        val eventStr = ev match
                                        {
                                            case AgentEvent.IterationBoundary(i) =>
                                                sseEvent("iteration", s"""{"iteration":$i}""")
                                            case AgentEvent.ToolCallStart(t, inp) =>
                                                sseEvent("tool_start", s"""{"tool":"$t","input":$inp}""")
                                            case AgentEvent.ToolCallResult(t, out, ms) =>
                                                sseEvent("tool_result", s"""{"tool":"$t","output":$out,"durationMs":$ms}""")
                                            case AgentEvent.Final(msgId) =>
                                                sseEvent("final", s"""{"messageId":"$msgId"}""")
                                            case AgentEvent.Cancelled =>
                                                sseEvent("cancelled", "{}")
                                            case AgentEvent.AgentError(msg) =>
                                                sseEvent("error", s"""{"message":"${msg.replace("\"", "\\\"")}"}""")
                                        }
                                        queue.offer(eventStr)
                                        ev match
                                        {
                                            case AgentEvent.Final(_) | AgentEvent.Cancelled | AgentEvent.AgentError(_) =>
                                                queue.offer(sseEvent("done", "{}"))
                                            case _ => ()
                                        }
                                    }
                                )
                            }
                            catch
                            {
                                case ex: Exception =>
                                    TraceLogger.error(traceId, "run_error", Map("error" -> ex.getMessage))
                                    queue.offer(sseEvent("error", s"""{"message":"${ex.getMessage.replace("\"", "\\\"")}"}"""))
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
    /** Opens an SSE stream for the given `runId`.
     *  Blocks (on a virtual thread) on the run's event queue and emits each event as it arrives.
     *  Closes the stream when a `done`, `final`, or `cancelled` frame is dequeued.
     */
    @cask.route("/sessions/:id/stream/:runId", methods = Seq("get", "options"))
    def streamRun(id: String, runId: String, request: Request): Response[Response.Data] =
    {
        if request.exchange.getRequestMethod.toString == "OPTIONS" then
            Response("", statusCode = 204, headers = corsHeaders)
        else Auth.validate(request) match
        {
            case Left(err) =>
                Response(s"""{"error":"$err"}""", statusCode = 401,
                    headers = Seq("Content-Type" -> "application/json"))
            case Right(_) =>
                val queue = sseQueues.get(runId)
                if queue == null then
                    Response("""{"error":"run not found"}""", statusCode = 404,
                        headers = Seq("Content-Type" -> "application/json"))
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
                            if ev.contains("event: done") || ev.contains("event: final") || ev.contains("event: cancelled") then
                                done = true
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

    /** Sets the cancellation flag for the given `runId`; the agent loop checks it between tokens.
     *  Returns 204 regardless of whether the run exists.
     */
    @cask.route("/runs/:runId", methods = Seq("delete", "options"))
    def cancelRun(runId: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val flag = cancelFlags.get(runId)
            if flag != null then flag.set(true)
            Response("", statusCode = 204)
        }
    }

    // --- Token usage ---

    /** Returns all token-usage records for session `id` in chronological order. */
    @cask.route("/sessions/:id/token-usage", methods = Seq("get", "options"))
    def tokenUsage(id: String, request: Request): Response[Response.Data] =
    {
        withAuth(request) {
            val usage = runStore.tokenUsageForSession(id)
            Response(write(usage), headers = Seq("Content-Type" -> "application/json"))
        }
    }

    initialize()
}
