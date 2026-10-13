package agentica.server

import java.io.File
import java.nio.file.{Path, Paths}

/**
 *  Allowlist of permitted workspace roots for `POST /sessions` `rootPath`.
 *
 *  Without a boundary, an API caller can point a session's sandbox at `/` or `$HOME`
 *  and let `files.*` tools read/write anywhere on the host. Roots are configured via
 *  the `AGENTICA_ALLOWED_ROOTS` environment variable as a [[File.pathSeparator]]
 *  separated list (`:` on Linux/macOS, `;` on Windows). When unset, the allowlist
 *  falls back to the `allowedRoots` application setting, then to the OS user
 *  home directory.
 */
object AllowedRoots
{
    private val caseInsensitive: Boolean =
        System.getProperty("os.name").toLowerCase.contains("win")

    /**
     *  Resolves a path to its absolute, normalized form for boundary comparison.
     *
     *  @param p  Path as supplied by config or the API caller; may be relative.
     *  @return   Absolute path with `.`/`..` segments collapsed.
     */
    private def normalize(p: Path): Path = p.toAbsolutePath.normalize()

    /**
     *  Configured allowed roots, resolved to absolute normalized paths.
     *
     *  @param envValue       Raw `AGENTICA_ALLOWED_ROOTS` value; defaults to the
     *                        process environment and takes precedence when set.
     *  @param settingsValue  `allowedRoots` value from [[agentica.settings.AppSettings]];
     *                        used when the environment variable is absent or blank.
     *  @return               Non-empty list of permitted workspace root directories;
     *                        `user.home` when neither source is configured.
     */
    def roots(envValue:       Option[String] = sys.env.get("AGENTICA_ALLOWED_ROOTS"),
              settingsValue:  Option[String] = None): List[Path] =
        envValue.filter(_.trim.nonEmpty).orElse(settingsValue.filter(_.trim.nonEmpty)) match
        {
            case Some(csv) =>
                csv.split(File.pathSeparator).toList
                    .map(s => normalize(Paths.get(s.trim)))
            case _ =>
                List(normalize(Paths.get(System.getProperty("user.home"))))
        }

    /**
     *  Returns `true` when `rootPath` resolves inside at least one allowed root.
     *  Comparison is element-wise via [[Path.startsWith]] on POSIX systems and a
     *  case-insensitive string prefix match on Windows (whose filesystem is
     *  case-insensitive).
     *
     *  @param rootPath  Candidate workspace root supplied by the API caller.
     *  @param allowed   Roots to check against; defaults to [[roots]] from the environment.
     *  @return          Whether the path is permitted as a session root.
     */
    def isAllowed(rootPath: String, allowed: List[Path] = roots()): Boolean =
    {
        try
        {
            val p = normalize(Paths.get(rootPath))
            if (caseInsensitive)
            {
                val s = p.toString.toLowerCase
                allowed.exists { r =>
                    val rs = r.toString.toLowerCase
                    s == rs || s.startsWith(rs + File.separator)
                }
            }
            else allowed.exists(r => p.startsWith(r))
        }
        catch { case _: Exception => false }
    }
}
