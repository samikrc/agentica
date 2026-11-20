package agentica.testutil

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/**
 *  Machine-local test configuration shared by the VLM harness, the document
 *  eval harness, and `run-eval-loop.sh`.
 *
 *  Keys resolve in this order: system property → `local.conf` → environment
 *  variable. `local.conf` is `KEY=VALUE` per line (shell-sourceable), `#` starts
 *  a full-line comment, and optional surrounding quotes are stripped. The file
 *  lives next to this class on the test classpath and is gitignored;
 *  `local.conf.example` documents the supported keys. Point
 *  `AGENTICA_LOCAL_CONF` at a different file to override the location.
 *
 *  Secrets belong in environment variables, not in `local.conf`.
 */
object LocalConf
{
    /** Classpath resource read when no explicit file is configured. */
    val ResourceName: String = "/local.conf"

    /**
     *  Parses `KEY=VALUE` lines, ignoring blanks and full-line comments.
     *  @param text  File content.
     *  @return      Key/value pairs; later duplicates win.
     */
    def parse(text: String): Map[String, String] =
        text.linesIterator.foldLeft(Map.empty[String, String]) { (acc, rawLine) =>
            val line = rawLine.trim
            if (line.isEmpty || line.startsWith("#")) acc
            else
            {
                line.split("=", 2) match
                {
                    case Array(key, value) if key.trim.nonEmpty =>
                        acc.updated(key.trim, unquote(value.trim))
                    case _ => acc
                }
            }
        }

    /**
     *  Strips one pair of matching single or double quotes.
     *  @param value  Raw value text.
     *  @return       Unquoted value.
     */
    private def unquote(value: String): String =
        if (value.length >= 2 && (value.head == '"' || value.head == '\'') && value.last == value.head)
            value.substring(1, value.length - 1)
        else
            value

    /**
     *  Reads the configured file, preferring `AGENTICA_LOCAL_CONF` over the
     *  classpath resource.
     *  @return  Parsed entries, empty when no file exists.
     */
    private def load(): Map[String, String] =
    {
        val explicit = sys.props.get("AGENTICA_LOCAL_CONF").orElse(sys.env.get("AGENTICA_LOCAL_CONF"))
        explicit match
        {
            case Some(path) => readFile(Paths.get(path))
            case None =>
                Option(getClass.getResourceAsStream(ResourceName))
                    .map(stream => try new String(stream.readAllBytes(), "UTF-8") finally stream.close())
                    .map(parse)
                    .getOrElse(Map.empty)
        }
    }

    /**
     *  Reads and parses a file path, returning an empty map when unreadable.
     *  @param path  File to read.
     *  @return      Parsed entries.
     */
    private def readFile(path: Path): Map[String, String] =
        Try(Files.readString(path)).toOption.map(parse).getOrElse(Map.empty)

    private lazy val entries: Map[String, String] = load()

    /**
     *  Resolves a configuration key.
     *  @param key  Key name, e.g. `"LM_STUDIO_URL"`.
     *  @return     The value from a system property, `local.conf`, or the
     *              environment, or `None` when unset everywhere.
     */
    def get(key: String): Option[String] =
        sys.props.get(key).filter(_.nonEmpty)
            .orElse(entries.get(key).filter(_.nonEmpty))
            .orElse(sys.env.get(key).filter(_.nonEmpty))

    /**
     *  Resolves a key with a fallback.
     *  @param key      Key name.
     *  @param fallback Value used when the key is unset everywhere.
     *  @return         The resolved value.
     */
    def getOrElse(key: String, fallback: => String): String = get(key).getOrElse(fallback)

    /**
     *  Resolves an integer key with a fallback.
     *  @param key      Key name.
     *  @param fallback Value used when the key is unset or not an integer.
     *  @return         The resolved integer.
     */
    def getInt(key: String, fallback: => Int): Int = get(key).flatMap(_.toIntOption).getOrElse(fallback)
}
