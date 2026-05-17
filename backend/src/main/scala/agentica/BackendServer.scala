package agentica

import agentica.agent.{AgentLoop, ContextManager}
import agentica.llm.{OllamaProvider, OpenAIProvider}
import agentica.observability.{TokenAccounting, TraceLogger}
import agentica.permissions.ScopeStoreImpl
import agentica.platform.AppDirs
import agentica.server.Routes
import agentica.session.{AgentTurnStore, MemoryStoreImpl, MessageStore, RunStore, SessionStore}
import agentica.settings.SettingsStore
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.tools.files.{FilesList, FilesRead, FilesSearch, FilesStat, FilesWrite}
import agentica.tools.memory.{MemoryGet, MemoryList, MemorySet}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.net.ServerSocket
import java.nio.file.{Path, Paths}

/** Application entry point for the Agentica local backend.
 *  Initialises the database, wires dependencies, prints the selected port,
 *  then starts the Cask HTTP server.
 */
object BackendServer extends cask.Main 
{
    // Required for HttpClient to send the Connection header (used by OpenAIProvider).
    // Must be set before any HttpClient is constructed; doing it here covers all launch modes
    // (mvn exec:java, fat-jar, tests) without relying on JVM command-line flags.
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

    val sessionStore   = SessionStore(conn)
    val messageStore   = MessageStore(conn)
    val runStore       = RunStore(conn)
    val agentTurnStore = AgentTurnStore(conn)
    val settingsStore  = SettingsStore(AppDirs.settingsPath)
    val memoryStore    = MemoryStoreImpl(conn)
    val scopeStore     = ScopeStoreImpl(conn)

    sessionStore.init()
    messageStore.init()
    runStore.init()
    agentTurnStore.init()
    memoryStore.init()
    scopeStore.init()

    // --- Settings + Dependencies ---
    val settings      = settingsStore.load()
    val llmProvider   = sys.env.getOrElse("LLM_PROVIDER", "openai")
    val llm           = llmProvider match
    {
        case "ollama" =>
            val baseUrl = sys.env.getOrElse("OLLAMA_BASE_URL", "http://localhost:11434")
            val model   = sys.env.getOrElse("OLLAMA_MODEL", "llama3.2")
            OllamaProvider(baseUrl = baseUrl, modelName = model)
        case _ =>
            OpenAIProvider(
                baseUrl   = settings.serverUrl,
                modelName = settings.modelName,
                apiKey    = sys.env.getOrElse("LLM_API_KEY", "lm-studio")
            )
    }
    val accounting    = TokenAccounting(runStore)

    val commandRegistry = CommandRegistry()
    commandRegistry.register(FilesRead)
    commandRegistry.register(FilesWrite)
    commandRegistry.register(FilesList)
    commandRegistry.register(FilesSearch)
    commandRegistry.register(FilesStat)
    commandRegistry.register(MemorySet)
    commandRegistry.register(MemoryGet)
    commandRegistry.register(MemoryList)

    ContextManager.applyToolIndex(commandRegistry.helpIndex)

    val virtualShell = VirtualShell(commandRegistry)
    val agentEngine  = AgentLoop(llm, messageStore, runStore, agentTurnStore, accounting, virtualShell, settings,
        scopeStore, memoryStore, () => java.util.concurrent.SynchronousQueue[agentica.permissions.GrantDecision]())

    // UI root: AGENTICA_UI_ROOT env var, or ../ui relative to the working directory
    val uiRoot: Path  = Paths.get(sys.env.getOrElse("AGENTICA_UI_ROOT", "../ui")).toAbsolutePath.normalize()

    // --- Logging startup ---
    TraceLogger.info("-", "backend_start", Map(
        "port"     -> port.toString,
        "db"       -> AppDirs.dbPath.toString,
        "provider" -> llmProvider,
        "model"    -> llm.modelName
    ))

    // --- Announce selected port for launch scripts and future launchers ---
    println(s"Starting backend on PORT=$port")
    System.out.flush()

    // --- Start HTTP server ---
    override val allRoutes = Seq(Routes(sessionStore, messageStore, runStore, agentTurnStore, settingsStore, memoryStore, scopeStore, commandRegistry, agentEngine, uiRoot))
    TraceLogger.info("-", "http_server_start", Map("port" -> port.toString))
}
