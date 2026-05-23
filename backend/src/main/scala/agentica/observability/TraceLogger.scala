package agentica.observability

import agentica.platform.AppDirs
import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import ujson.Obj

/**
 *  Structured JSON-lines logger.
 *  Thread-safe; rotates log file when it exceeds maxBytes.
 *  Output: one JSON object per line with at minimum {ts, level, traceId, msg}.
 */
object TraceLogger
{

    private val lock     = ReentrantLock()
    private val maxBytes = 10L * 1024 * 1024  // 10 MB rotation threshold
    private var writer: PrintWriter = openWriter()

    /**
     *  Opens (or re-opens) the log file in append mode and returns a flushing [[PrintWriter]].
     */
    private def openWriter(): PrintWriter =
    {
        PrintWriter(FileWriter(AppDirs.logFile.toFile, true), true)
    }

    /**
     *  Rotates the active log file: closes the current writer, archives the file with a
     *  millisecond timestamp suffix, then opens a fresh writer on the canonical log path.
     */
    private def rotate(): Unit =
    {
        writer.close()
        val archive = AppDirs.logsDir.resolve(s"agentica-${Instant.now().toEpochMilli}.log")
        Files.move(AppDirs.logFile, archive)
        writer = openWriter()
    }

    /**
     *  Formats and writes a single JSON-lines log record, rotating the file first if necessary.
     *  Acquires [[lock]] for the duration; safe to call from any thread.
     *  @param level    Log level string: "INFO", "WARN", or "ERROR".
     *  @param traceId  Trace identifier associated with the event.
     *  @param msg      Event name or free-form message.
     *  @param extra    Additional string fields merged into the JSON object.
     */
    private def emit(level: String, traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        lock.lock()
        try
        {
            if AppDirs.logFile.toFile.exists() && AppDirs.logFile.toFile.length() > maxBytes then rotate()
            val obj = ujson.Obj(
                "ts"      -> Instant.now().toString,
                "level"   -> level,
                "traceId" -> traceId,
                "msg"     -> msg
            )
            extra.foreach { case (k, v) => obj(k) = v }
            writer.println(obj.render())
        }
        finally
        {
            lock.unlock()
        }
    }

    /**
     *  Emits an informational structured log line.
     *  @param traceId  Trace identifier associated with the event.
     *  @param msg      Event name or message.
     *  @param extra    Additional string fields to include in the JSON line.
     */
    def info(traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        emit("INFO", traceId, msg, extra)
    }

    /**
     *  Emits a warning structured log line.
     *  @param traceId  Trace identifier associated with the event.
     *  @param msg      Event name or message.
     *  @param extra    Additional string fields to include in the JSON line.
     */
    def warn(traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        emit("WARN", traceId, msg, extra)
    }

    /**
     *  Emits an error structured log line.
     *  @param traceId  Trace identifier associated with the event.
     *  @param msg      Event name or message.
     *  @param extra    Additional string fields to include in the JSON line.
     */
    def error(traceId: String, msg: String, extra: Map[String, String] = Map.empty): Unit =
    {
        emit("ERROR", traceId, msg, extra)
    }
}
