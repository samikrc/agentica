package agentica.settings

import upickle.default.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** 
 *  User-configurable application settings persisted outside the SQLite database.
 *  @param theme          UI theme identifier, currently `"light"` or `"dark"`.
 *  @param showStatusLine Whether to show the status line at the bottom of the chat pane.
 *  @param serverUrl      Base URL of the OpenAI-compatible LLM server.
 *  @param modelName      Model identifier sent to the LLM server.
 */
case class AppSettings(
    theme: String = "light",
    showStatusLine: Boolean = false,
    serverUrl: String = "http://172.23.64.1:1234",
    modelName: String = "mistralai/ministral-3-14b-reasoning"
) derives ReadWriter

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
     *  @param settings  Settings submitted by the caller.
     *  @return          The normalized settings that were written.
     */
    def save(settings: AppSettings): AppSettings =
    {
        this.synchronized {
            val normalized = normalize(settings)
            Files.createDirectories(path.getParent)
            Files.writeString(
                path,
                write[AppSettings](normalized, indent = 2),
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
        settings.copy(theme = theme)
    }
}
