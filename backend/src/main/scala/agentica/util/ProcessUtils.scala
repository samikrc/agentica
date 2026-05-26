package agentica.util

import java.util.concurrent.TimeUnit

/**
 *  Process execution utilities for running external commands.
 */
object ProcessUtils
{

    /**
     *  Runs a process, waits for it to complete (up to timeoutSec seconds), and returns stdout trimmed.
     *  Returns `None` on non-zero exit, timeout, or any exception.
     *
     *  @param cmd         Command and arguments as a list.
     *  @param timeoutSec  Maximum seconds to wait for process completion (default: 5).
     *  @return            Some(stdout) on success, None otherwise.
     */
    def runCaptured(cmd: List[String], timeoutSec: Long = 5): Option[String] =
    {
        try
        {
            val pb      = ProcessBuilder(cmd*)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val stdout  = String(process.getInputStream.readAllBytes())
            val exited  = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (exited && process.exitValue() == 0) Some(stdout.trim)
            else None
        }
        catch
        {
            case _: Exception => None
        }
    }
}
