package agentica.shell

import java.nio.file.{Path, Paths}

/**
 *  Utility that resolves and validates filesystem paths against a session root.
 *
 *  Every file-touching tool must call [[PathSandbox.resolve]] before performing
 *  any I/O.  The check prevents directory-traversal attacks (e.g. `../../../etc/passwd`)
 *  from escaping the session's working directory.
 */
object PathSandbox
{
    /**
     *  Resolves `argPath` relative to `rootPath` and verifies that the result
     *  remains inside the root directory.
     *
     *  @param rootPath  Absolute root directory for the session (must already be normalized).
     *  @param argPath   Path value supplied by the agent, may be relative or absolute.
     *  @return          `Right(resolvedAbsolutePath)` if the path is within the sandbox,
     *                   `Left("path_escaped")` if it escapes the root.
     */
    def resolve(rootPath: String, argPath: String): Either[String, Path] =
    {
        val root     = Paths.get(rootPath).toAbsolutePath.normalize()
        val resolved = root.resolve(argPath).normalize()
        if resolved.startsWith(root) then Right(resolved)
        else Left("path_escaped")
    }

    /**
     *  Returns `true` if `argPath` resolves to a location inside `rootPath`.
     *  Convenience wrapper around [[resolve]] for boolean checks.
     */
    def isWithin(rootPath: String, argPath: String): Boolean =
        resolve(rootPath, argPath).isRight
}
