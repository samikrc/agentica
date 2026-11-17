package agentica.shell

import java.nio.file.{Files, Path, Paths}

/**
 *  Utility that resolves and validates filesystem paths against a session root.
 *
 *  Every file-touching tool must call [[PathSandbox.resolve]] before performing
 *  any I/O.  The check prevents directory-traversal attacks (e.g. `../../../etc/passwd`)
 *  and symlink escapes (e.g. `workspace/link -> /etc`) from reaching outside the
 *  session's working directory.
 */
object PathSandbox
{
    /**
     *  Resolves `argPath` relative to `rootPath` and verifies that the result
     *  remains inside the root directory after symlink resolution.
     *
     *  For paths that do not exist yet (e.g. `files.write` creating a new file),
     *  the nearest existing ancestor is resolved to its real path first, so a
     *  symlinked parent cannot smuggle a new file outside the root.
     *
     *  @param rootPath  Absolute root directory for the session.
     *  @param argPath   Path value supplied by the agent, may be relative or absolute.
     *  @return          `Right(resolvedRealPath)` if the path is within the sandbox,
     *                   `Left("path_escaped")` if it escapes the root or cannot be resolved.
     */
    def resolve(rootPath: String, argPath: String): Either[String, Path] =
    {
        val rootAbs  = Paths.get(rootPath).toAbsolutePath.normalize()
        val rootReal = try rootAbs.toRealPath()
            catch { case _: java.io.IOException => rootAbs }  // non-existent root: normalized path is the best available anchor

        val candidate = rootReal.resolve(argPath).normalize()
        val real = if (Files.exists(candidate)) candidate.toRealPath()
            else
            {
                var ancestor = candidate.getParent
                while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent
                if (ancestor == null) return Left("path_escaped")
                ancestor.toRealPath().resolve(ancestor.relativize(candidate))
            }

        if (real.startsWith(rootReal)) Right(real)
        else Left("path_escaped")
    }

    /**
     *  Returns `true` if `argPath` resolves to a location inside `rootPath`.
     *  Convenience wrapper around [[resolve]] for boolean checks.
     */
    def isWithin(rootPath: String, argPath: String): Boolean =
        resolve(rootPath, argPath).isRight
}
