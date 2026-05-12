package agentica.platform

import java.nio.file.{Files, Path, Paths}

/** 
 *  Resolves Agentica's OS-standard data, log, database, and settings locations.
 *  The base data directory can be overridden with `AGENTICA_DATA_DIR`.
 */
object AppDirs
{

    private val appName = "Agentica"

    /** 
     *  Returns the OS-standard application data directory for Agentica.
     *  Windows : %APPDATA%\Agentica
     *  macOS   : ~/Library/Application Support/Agentica
     *  Linux   : ~/.local/share/Agentica
     */
    val dataDir: Path =
    {
        val base = sys.env.get("AGENTICA_DATA_DIR") match
        {
            case Some(override_) => Paths.get(override_)
            case None =>
                val os = sys.props("os.name").toLowerCase
                if (os.contains("win"))
                {
                    Paths.get(sys.env.getOrElse("APPDATA", sys.props("user.home")), appName)
                }
                else if (os.contains("mac"))
                {
                    Paths.get(sys.props("user.home"), "Library", "Application Support", appName)
                }
                else
                {
                    Paths.get(sys.props("user.home"), ".local", "share", appName)
                }
        }
        Files.createDirectories(base)
        base
    }

    /** Absolute path to the application SQLite database file. */
    val dbPath: Path  = dataDir.resolve("agentica.db")

    /** Absolute path to the JSON settings file. */
    val settingsPath: Path = dataDir.resolve("settings.json")

    /** Directory containing Agentica log files. */
    val logsDir: Path = Files.createDirectories(dataDir.resolve("logs"))

    /** Default backend log file path. */
    val logFile: Path = logsDir.resolve("agentica.log")
}
