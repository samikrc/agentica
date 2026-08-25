package agentica

import agentica.agent.{AgentLoop, ContextManager}
import agentica.llm.{LLMProvider, OllamaProvider, OpenAIProvider}
import agentica.observability.{TokenAccounting, TraceLogger}
import agentica.permissions.ScopeStoreImpl
import agentica.platform.AppDirs
import agentica.server.Routes
import agentica.session.{AgentTurnStore, MemoryStoreImpl, MessageStore, RunStore, SessionStore}
import agentica.settings.{AppSettings, SettingsStore}
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.doc.{DocFontLoader, DocToolDetector}
import agentica.tools.deps.DepsCheck
import agentica.tools.files.{FilesList, FilesRead, FilesReadDOCXToMarkdown, FilesReadPDFToMarkdown, FilesReadPPTXToMarkdown, FilesSearch, FilesStat, FilesWrite}
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

    // PDFBox uses AWT (Graphics2D) for page rendering. On a headless Linux server without
    // a display, AWT's font subsystem can hang indefinitely trying to connect to X11.
    // This must be set before any PDFBox or ImageIO class is loaded.
    System.setProperty("java.awt.headless", "true")

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
    //override val port = sys.env.get("AGENTICA_PORT").flatMap(_.toIntOption).getOrElse(findFreePort())
    override val port = sys.env.getOrElse("AGENTICA_PORT", "11211").toInt
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

    // --- Document tool detection + font registration ---
    DocToolDetector.status   // eagerly trigger detection; result is cached
    DocFontLoader.init()

    // --- Settings + Dependencies ---
    val settings        = settingsStore.load()
    val llmProviderType = sys.env.getOrElse("LLM_PROVIDER", "openai")

    /**
     *  Constructs the primary [[LLMProvider]] from the given settings.
     *  When `LLM_PROVIDER=ollama`, uses [[OllamaProvider]] with env-supplied base URL and model.
     *  Otherwise defaults to [[OpenAIProvider]] using the settings' server URL, model, and API key.
     *  @param s  Application settings to read server URL, model name, and API key from.
     *  @return   A freshly constructed [[LLMProvider]] instance.
     */
    def buildLLMProvider(settings: AppSettings): LLMProvider = llmProviderType match
    {
        case "ollama" =>
            val baseURL = sys.env.getOrElse("OLLAMA_BASE_URL", "http://localhost:11434")
            val model   = sys.env.getOrElse("OLLAMA_MODEL", "llama3.2")
            OllamaProvider(baseURL = baseURL, modelName = model)
        case _ =>
            val apiKey = if settings.apiKey.nonEmpty then settings.apiKey
                         else sys.env.getOrElse("LLM_API_KEY", "lm-studio")
            OpenAIProvider(
                baseURL = settings.serverURL,
                modelName = settings.modelName,
                apiKey = apiKey
            )
    }

    /**
     *  Constructs an optional Vision [[LLMProvider]] from the given settings.
     *  Returns `Some` only when both `vlmServerURL` and `vlmModel` are non-empty.
     *  Falls back to the `VLM_API_KEY` environment variable when no API key is configured.
     *  @param settings  Application settings to read VLM server URL, model name, and API key from.
     *  @return          `Some` [[OpenAIProvider]] configured for vision calls, or `None` if VLM is not configured.
     */
    def buildVLMProvider(settings: AppSettings): Option[LLMProvider] =
        val hasUrl   = settings.vlmServerURL.nonEmpty
        val hasModel = settings.vlmModel.nonEmpty
        if hasUrl && hasModel then
            val vlmAPIKey = if settings.vlmAPIKey.nonEmpty then settings.vlmAPIKey
                            else sys.env.getOrElse("VLM_API_KEY", "lm-studio")
            Some(OpenAIProvider(
                baseURL = settings.vlmServerURL,
                modelName = settings.vlmModel,
                apiKey = vlmAPIKey
            ))
        else
            if hasUrl || hasModel then
                TraceLogger.warn("-", "vlm_provider_partial_config",
                    Map("hasUrl" -> hasUrl.toString, "hasModel" -> hasModel.toString,
                        "vlmServerURL" -> settings.vlmServerURL, "vlmModel" -> settings.vlmModel))
            None

    val llmProvider = buildLLMProvider(settings)
    val vlmProvider = buildVLMProvider(settings)
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
    commandRegistry.register(DepsCheck)
    commandRegistry.register(FilesReadPDFToMarkdown)
    commandRegistry.register(FilesReadDOCXToMarkdown)
    commandRegistry.register(FilesReadPPTXToMarkdown)

    ContextManager.applyToolIndex(commandRegistry.helpIndex)

    val virtualShell = VirtualShell(commandRegistry)
    val agentEngine  = AgentLoop(
        llmProvider,
        vlmProvider,
        messageStore,
        runStore,
        agentTurnStore,
        accounting,
        virtualShell,
        settings,
        scopeStore,
        memoryStore,
        sessionStore
    )

    // UI root: AGENTICA_UI_ROOT env var, or ../ui relative to the working directory
    val uiRoot: Path  = Paths.get(sys.env.getOrElse("AGENTICA_UI_ROOT", "../ui")).toAbsolutePath.normalize()

    // --- Logging startup ---
    TraceLogger.info("-", "backend_start", Map(
        "port"     -> port.toString,
        "db"       -> AppDirs.dbPath.toString,
        "provider" -> llmProviderType,
        "model"    -> llmProvider.modelName,
        "vlm"      -> vlmProvider.map(_.modelName).getOrElse("(using primary LLM)")
    ))

    // --- Announce selected port for launch scripts and future launchers ---
    println(s"Starting backend on PORT=$port")
    System.out.flush()

    // --- Start HTTP server ---
    override val allRoutes = Seq(Routes(
        sessionStore    = sessionStore,
        messageStore    = messageStore,
        runStore        = runStore,
        agentTurnStore  = agentTurnStore,
        settingsStore   = settingsStore,
        memoryStore     = memoryStore,
        scopeStore      = scopeStore,
        commandRegistry = commandRegistry,
        agentEngine     = agentEngine,
        uiRoot          = uiRoot,
        onSettingsSaved = s => agentEngine.updateProviders(buildLLMProvider(s), buildVLMProvider(s))
    ))
    TraceLogger.info("-", "http_server_start", Map("port" -> port.toString))
}
