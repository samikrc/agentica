package agentica.observability

import agentica.platform.AppDirs
import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/** Structured JSON-lines logger.
 *  Thread-safe; rotates log file when it exceeds maxBytes.
 *  Output: one JSON object per line with at minimum {ts, level, traceId, msg}.
 */
object TraceLogger
{

    private val lock     = ReentrantLock()
    private val maxBytes = 10L * 1024 * 1024  // 10 MB rotation threshold
    private var writer: PrintWriter = openWriter()

    private def openWriter(): PrintWriter =
    {
        PrintWriter(FileWriter(AppDirs.logFile.toFile, true), true)
    }

    private def rotate(): Unit =
    {
        writer.close()
        val archive = AppDirs.logsDir.resolve(s"agentica-${Instant.now().toEpochMilli}.log")
        Files.move(AppDirs.logFile, archive)
        writer = openWriter()
    }

    private def emit(level: String, traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        lock.lock()
        try
        {
            if AppDirs.logFile.toFile.exists() && AppDirs.logFile.toFile.length() > maxBytes then rotate()
            val fields = (Map(
                "ts"      -> Instant.now().toString,
                "level"   -> level,
                "traceId" -> traceId,
                "msg"     -> msg
            ) ++ extra).map { case (k, v) =>
                s""""$k":"${v.replace("\"", "\\\"").replace("\n", "\\n")}""""
            }.mkString("{", ",", "}")
            writer.println(fields)
        }
        finally
        {
            lock.unlock()
        }
    }

    def info(traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        emit("INFO", traceId, msg, extra)
    }

    def warn(traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        emit("WARN", traceId, msg, extra)
    }

    def error(traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        emit("ERROR", traceId, msg, extra)
    }
}
