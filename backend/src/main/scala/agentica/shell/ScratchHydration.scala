package agentica.shell

import java.nio.file.Files

/**
 *  Re-hydrates file-backed scratch refs by re-reading the workspace file.
 *
 *  Refs persist in conversation history, but the pad is per-run and in-memory,
 *  so a later turn can reference an entry that no longer exists. The workspace
 *  file is the source of truth, so re-reading it re-derives the content exactly;
 *  computed keys (`__result_N__`) have no backing file and are never hydrated.
 *  Shared by [[VirtualShell]] (argument substitution) and
 *  [[agentica.tools.files.FilesSearch]] (`ref=` drill-down).
 */
object ScratchHydration
{
    /** Maximum file size re-hydrated from disk when a scratch ref has expired. */
    val MaxBytes: Long = 10L * 1024 * 1024

    /**
     *  Reads the workspace file behind a file-backed scratch ref and re-stores
     *  it in the pad.
     *  @param ref         Scratch ref, e.g. `$scratch/data/report.md`.
     *  @param scratchpad  Pad receiving the re-stored entry.
     *  @param rootPath    Workspace root the key is resolved against.
     *  @return            The re-stored entry, or `None` when the key is computed
     *                     or the file is missing, oversized, or unreadable.
     */
    def hydrate(ref: String, scratchpad: SessionScratchpad, rootPath: String): Option[ScratchEntry] =
    {
        val key = ref.stripPrefix("$scratch/")
        if (key.startsWith("__result_")) return None
        PathSandbox.resolve(rootPath, key).toOption
            .filter(Files.isRegularFile(_))
            .filter(p => Files.size(p) <= MaxBytes)
            .flatMap { path =>
                try
                {
                    val content = Files.readString(path)
                    val now     = System.currentTimeMillis()
                    val entry   = ScratchEntry(
                        content      = content,
                        sizeBytes    = Files.size(path),
                        lineCount    = content.count(_ == '\n') + 1,
                        sourcePath   = key,
                        lastModified = Files.getLastModifiedTime(path).toMillis,
                        storedAt     = now
                    )
                    scratchpad.store(key, entry)
                    Some(entry)
                }
                catch
                {
                    case _: Throwable => None
                }
            }
    }
}
