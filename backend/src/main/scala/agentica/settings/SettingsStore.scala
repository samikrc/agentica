package agentica.settings

import upickle.default.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** LLM API endpoint to use for inference calls. */
enum APIMode:
    case ChatCompletions // Chat Completions — stateless, full context sent each turn (default)
    case Responses       // Responses API   — stateful, server retains context via response ID

object APIMode:
    given ReadWriter[APIMode] = readwriter[String].bimap(
        mode => mode.toString.toLowerCase,
        str  => str.toLowerCase match
            case "responses" => Responses
            case _           => ChatCompletions
    )

/**
 *  User-configurable application settings persisted outside the SQLite database.
 *  @param theme                UI theme identifier, currently `"light"` or `"dark"`.
 *  @param showStatusLine       Whether to show the status line at the bottom of the chat pane.
 *  @param serverURL            Base URL of the OpenAI-compatible LLM server.
 *  @param apiKey               API key for the LLM server (empty string means no auth / local server).
 *  @param modelName            Model identifier sent to the LLM server.
 *  @param maxIterations        Maximum number of plan→act→observe loop iterations per agent run.
 *  @param contextBudgetTokens  Approximate token budget for the sliding context window.
 *  @param apiMode              LLM API to use: [[APIMode.ChatCompletions]] (default) or [[APIMode.Responses]].
 *  @param vlmServerURL         Base URL of the Vision LLM server (empty = use primary LLM).
 *  @param vlmAPIKey            API key for the VLM server (empty string means no auth / local server).
 *  @param vlmModel             Model identifier for Vision LLM calls (empty = use primary model).
 *  @param debugMode            When true, page images sent to the VLM are saved alongside the source document
 *                              in a `<stem>_debug/` subdirectory for inspection.
 *  @param vlmParallelism       Number of concurrent VLM calls when transcribing document pages (default 4).
 *                              Values above 1 enable parallel page processing, useful with hosted APIs.
 */
case class AppSettings(
    theme:                String  = "light",
    showStatusLine:       Boolean = false,
    serverURL:            String  = "http://172.23.64.1:1234",
    apiKey:               String  = "",
    modelName:            String  = "mistralai/ministral-3-14b-reasoning",
    maxIterations:        Int     = 20,
    contextBudgetTokens:  Int     = 8000,
    apiMode:              APIMode = APIMode.ChatCompletions,
    vlmServerURL:         String  = "",
    vlmAPIKey:            String  = "",
    vlmModel:             String  = "",
    debugMode:            Boolean = false,
    vlmParallelism:       Int     = 4
)

object AppSettings:
    given ReadWriter[AppSettings] =
    {
        import APIMode.given
        macroRW[AppSettings]
    }

/** 
 *  JSON-backed store for Agentica application settings.
 *  The settings file is stored under [[agentica.platform.AppDirs.dataDir]],
 *  next to the SQLite database, and is created with defaults on first read.
 *  @param path  Absolute path to the `settings.json` file.
 */
class SettingsStore(path: Path)
{
    private val defaultSettings = AppSettings()

    /** 
     *  Loads settings from disk, creating a default file if none exists.
     *  Invalid or unreadable JSON falls back to default settings.
     */
    def load(): AppSettings =
    {
        this.synchronized {
            if !Files.exists(path) then
            {
                save(defaultSettings)
                defaultSettings
            }
            else
            {
                val text = Files.readString(path, StandardCharsets.UTF_8)
                try
                {
                    val parsed = read[AppSettings](text)
                    normalize(parsed)
                }
                catch
                {
                    case _: Exception => defaultSettings
                }
            }
        }
    }
    
    /**
     *  Persists settings to disk after normalizing supported values.
     *  Writes every field explicitly via ujson.Obj so the file is always
     *  self-documenting — upickle 4.x macroRW omits fields that equal their
     *  default value, which would produce an opaque {} for a default AppSettings.
     *  @param settings  Settings submitted by the caller.
     *  @return          The normalized settings that were written.
     */
    def save(settings: AppSettings): AppSettings =
    {
        this.synchronized {
            val normalized = normalize(settings)
            val json = ujson.Obj(
                "theme"               -> ujson.Str(normalized.theme),
                "showStatusLine"      -> ujson.Bool(normalized.showStatusLine),
                "serverURL"           -> ujson.Str(normalized.serverURL),
                "apiKey"              -> ujson.Str(normalized.apiKey),
                "modelName"           -> ujson.Str(normalized.modelName),
                "maxIterations"       -> ujson.Num(normalized.maxIterations),
                "contextBudgetTokens" -> ujson.Num(normalized.contextBudgetTokens),
                "apiMode"             -> ujson.Str(normalized.apiMode.toString.toLowerCase),
                "vlmServerURL"        -> ujson.Str(normalized.vlmServerURL),
                "vlmAPIKey"           -> ujson.Str(normalized.vlmAPIKey),
                "vlmModel"            -> ujson.Str(normalized.vlmModel),
                "debugMode"           -> ujson.Bool(normalized.debugMode),
                "vlmParallelism"      -> ujson.Num(normalized.vlmParallelism)
            )
            Files.createDirectories(path.getParent)
            Files.writeString(
                path,
                ujson.write(json, 2),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            normalized
        }
    }
    
    /** Restricts settings values to supported options. */
    private def normalize(settings: AppSettings): AppSettings =
    {
        val theme = settings.theme.toLowerCase match
        {
            case "dark" => "dark"
            case _      => "light"
        }
        val vlmParallelism = settings.vlmParallelism.max(1).min(32)
        settings.copy(theme = theme, vlmParallelism = vlmParallelism)
    }
}
