package agentica.search

import org.scalatest.funsuite.AnyFunSuite
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 *  Unit tests for [[TextExtractor]]: extension classification, binary sniffing,
 *  text-file reading, and text-layer extraction from the PDF/DOCX/PPTX fixtures.
 */
class TextExtractorTest extends AnyFunSuite
{

    /** Runs `f` with a fresh temp directory that is deleted afterwards. */
    private def withTempDir[A](f: Path => A): A =
    {
        val dir = Files.createTempDirectory("agentica-text-extractor-test")
        try f(dir)
        finally
        {
            if (Files.exists(dir))
                Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p => Files.deleteIfExists(p))
        }
    }

    /** Copies a classpath fixture into `dir` under `name` and returns the copy. */
    private def copyFixture(resource: String, dir: Path, name: String): Path =
    {
        val src = Paths.get(getClass.getResource(resource).toURI)
        Files.copy(src, dir.resolve(name))
    }

    test("extension classification") {
        assert(TextExtractor.extensionOf(Paths.get("a/b/report.MD")) == "md")
        assert(TextExtractor.extensionOf(Paths.get("noext")) == "")
        assert(TextExtractor.isSupported(Paths.get("a.md")))
        assert(TextExtractor.isSupported(Paths.get("a.pdf")))
        assert(!TextExtractor.isSupported(Paths.get("a.png")))
        assert(TextExtractor.isDocument(Paths.get("a.docx")))
        assert(!TextExtractor.isDocument(Paths.get("a.md")))
        assert(TextExtractor.isSkippedDir("node_modules"))
        assert(TextExtractor.isSkippedDir("doc_debug"))
        assert(!TextExtractor.isSkippedDir("reports"))
    }

    test("binary sniff detects NUL bytes and accepts UTF-8 text") {
        withTempDir { dir =>
            val text = dir.resolve("plain.txt")
            Files.writeString(text, "hello world", StandardCharsets.UTF_8)
            assert(!TextExtractor.looksBinary(text))

            val bin = dir.resolve("blob.txt")
            Files.write(bin, Array[Byte](1, 2, 0, 4))
            assert(TextExtractor.looksBinary(bin))
        }
    }

    test("text files are read as UTF-8; binary and unsupported files yield None") {
        withTempDir { dir =>
            val text = dir.resolve("notes.md")
            Files.writeString(text, "# Notes\nRevenue grew.", StandardCharsets.UTF_8)
            assert(TextExtractor.extract(text).contains("# Notes\nRevenue grew."))

            val bin = dir.resolve("blob.md")
            Files.write(bin, Array[Byte](0, 1, 2))
            assert(TextExtractor.extract(bin).isEmpty)

            val png = dir.resolve("image.png")
            Files.write(png, Array[Byte](1, 2, 3))
            assert(TextExtractor.extract(png).isEmpty)
        }
    }

    test("files above the size cap are not extracted") {
        withTempDir { dir =>
            val big = dir.resolve("big.txt")
            val buf = Array.fill[Byte](TextExtractor.MaxFileBytes.toInt + 1)('a'.toByte)
            Files.write(big, buf)
            assert(TextExtractor.extract(big).isEmpty)
        }
    }

    test("PDF text layer is extracted from the fixture") {
        withTempDir { dir =>
            val pdf = copyFixture("/files/IT Support Analyst - India.pdf", dir, "doc.pdf")
            val text = TextExtractor.extract(pdf)
            assert(text.isDefined, "PDF text layer should be extractable")
            assert(text.get.trim.nonEmpty)
        }
    }

    test("PPTX slide text is extracted from the fixture") {
        withTempDir { dir =>
            val pptx = copyFixture("/files/sample.pptx", dir, "sample.pptx")
            val text = TextExtractor.extract(pptx)
            assert(text.isDefined, "PPTX text should be extractable")
            assert(text.get.contains("--- slide 1 ---"))
        }
    }

    test("DOCX paragraph text is extracted from the fixture") {
        withTempDir { dir =>
            val docx = copyFixture("/files/sample.docx", dir, "sample.docx")
            val text = TextExtractor.extract(docx)
            assert(text.isDefined, "DOCX text should be extractable")
            assert(text.get.trim.nonEmpty)
        }
    }
}
