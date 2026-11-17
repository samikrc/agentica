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
        realPath(candidate) match
        {
            case Some(real) if real.startsWith(rootReal) => Right(real)
            case _ => Left("path_escaped")
        }
    }

    /**
     *  Resolves `path` to its canonical real path even when it does not exist.
     *  Existing paths resolve via [[Path.toRealPath]]; a symlink — including a
     *  dangling one — resolves through its link target; remaining non-existent
     *  suffixes resolve through their nearest resolvable ancestor.  Resolution
     *  gives up after [[MaxSymlinkDepth]] hops so symlink loops cannot spin.
     *
     *  @param path   Normalized absolute path to resolve.
     *  @param depth  Current symlink-resolution depth.
     *  @return       The real path, or `None` when no ancestor resolves or a
     *                symlink loop is detected.
     */
    private def realPath(path: Path, depth: Int = 0): Option[Path] =
        if (depth > MaxSymlinkDepth) None
        else if (Files.exists(path)) Some(path.toRealPath())
        else if (Files.isSymbolicLink(path))
            realPath(path.getParent.resolve(Files.readSymbolicLink(path)).normalize(), depth + 1)
        else if (path.getParent != null)
            realPath(path.getParent, depth).map(_.resolve(path.getFileName))
        else None

    private val MaxSymlinkDepth = 40

    /**
     *  Returns `true` if `argPath` resolves to a location inside `rootPath`.
     *  Convenience wrapper around [[resolve]] for boolean checks.
     */
    def isWithin(rootPath: String, argPath: String): Boolean =
        resolve(rootPath, argPath).isRight
}
