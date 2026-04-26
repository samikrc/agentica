package agentica.platform

import java.nio.file.{Files, Path, Paths}

object AppDirs
{

    private val appName = "Agentica"

    /** Returns the OS-standard application data directory for Agentica.
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
                if os.contains("win") then
                    Paths.get(sys.env.getOrElse("APPDATA", sys.props("user.home")), appName)
                else if os.contains("mac") then
                    Paths.get(sys.props("user.home"), "Library", "Application Support", appName)
                else
                    Paths.get(sys.props("user.home"), ".local", "share", appName)
        }
        Files.createDirectories(base)
        base
    }

    val dbPath: Path  = dataDir.resolve("agentica.db")
    val logsDir: Path = Files.createDirectories(dataDir.resolve("logs"))
    val logFile: Path = logsDir.resolve("agentica.log")
}
