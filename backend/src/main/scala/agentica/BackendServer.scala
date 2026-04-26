package agentica

import agentica.agent.AgentLoop
import agentica.llm.{OllamaProvider, OpenAIProvider}
import agentica.observability.{TokenAccounting, TraceLogger}
import agentica.platform.AppDirs
import agentica.server.Routes
import agentica.session.{MessageStore, RunStore, SessionStore}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.net.ServerSocket
import java.nio.file.{Path, Paths}

/** Application entry point for the Agentica backend sidecar.
 *  Initialises the database, wires dependencies, prints the port handshake line
 *  (`PORT=<n>`) for the Tauri shell to read, then starts the Cask HTTP server.
 */
object BackendServer extends cask.Main 
{
    // Required for HttpClient to send the Connection header (used by OpenAIProvider).
    // Must be set before any HttpClient is constructed; doing it here covers all launch modes
    // (mvn exec:java, fat-jar sidecar, tests) without relying on JVM command-line flags.
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection")

    /** Binds a [[java.net.ServerSocket]] on port 0 to let the OS pick a free port,
     *  then immediately releases the socket and returns the port number.
     */
    private def findFreePort(): Int =
    {
        val s = ServerSocket(0)
        val p = s.getLocalPort
        s.close()
        p
    }

    // --- Port selection + binding ---
    override val port = sys.env.get("AGENTICA_PORT").flatMap(_.toIntOption).getOrElse(findFreePort())
    override val host = "0.0.0.0"

    // --- Database setup ---
    val hikariConfig = HikariConfig()
    hikariConfig.setJdbcUrl(s"jdbc:sqlite:${AppDirs.dbPath}")
    hikariConfig.setMaximumPoolSize(4)
    hikariConfig.addDataSourceProperty("foreign_keys", "true")
    val ds   = HikariDataSource(hikariConfig)
    val conn = () => ds.getConnection()

    val sessionStore = SessionStore(conn)
    val messageStore = MessageStore(conn)
    val runStore     = RunStore(conn)

    sessionStore.init()
    messageStore.init()
    runStore.init()

    // --- Dependencies ---
    val llmProvider   = sys.env.getOrElse("LLM_PROVIDER", "openai")
    val llm           = llmProvider match
    {
        case "ollama" =>
            val baseUrl = sys.env.getOrElse("OLLAMA_BASE_URL", "http://localhost:11434")
            val model   = sys.env.getOrElse("OLLAMA_MODEL", "llama3.2")
            OllamaProvider(baseUrl = baseUrl, modelName = model)
        case _ =>
            val baseUrl = sys.env.getOrElse("LLM_BASE_URL", "http://localhost:1234")
            val model   = sys.env.getOrElse("LLM_MODEL", "local-model")
            val apiKey  = sys.env.getOrElse("LLM_API_KEY", "lm-studio")
            OpenAIProvider(baseUrl = baseUrl, modelName = model, apiKey = apiKey)
    }
    val accounting    = TokenAccounting(runStore)
    val agentEngine   = AgentLoop(llm, messageStore, accounting)

    // UI root: AGENTICA_UI_ROOT env var, or ../ui relative to the working directory
    val uiRoot: Path  = Paths.get(sys.env.getOrElse("AGENTICA_UI_ROOT", "../ui")).toAbsolutePath.normalize()

    // --- Logging startup ---
    TraceLogger.info("-", "sidecar_start", Map(
        "port"     -> port.toString,
        "db"       -> AppDirs.dbPath.toString,
        "provider" -> llmProvider,
        "model"    -> llm.modelName
    ))

    // --- Announce port to Tauri (port handshake protocol) ---
    println(s"Starting backend on PORT=$port")
    System.out.flush()

    // --- Start HTTP server ---
    override val allRoutes = Seq(Routes(sessionStore, messageStore, runStore, agentEngine, uiRoot))
    TraceLogger.info("-", "http_server_start", Map("port" -> port.toString))
}
