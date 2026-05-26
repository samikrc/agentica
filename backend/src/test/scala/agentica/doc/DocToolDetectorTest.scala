package agentica.doc

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Unit tests for [[DocToolDetector]] and [[DocFontLoader]].
 *
 *  These tests intentionally avoid mocking the filesystem or process execution because:
 *  - [[DocToolDetector]] caches its result lazily — we can only observe the cached value.
 *  - The detection result is environment-dependent (soffice may or may not be installed).
 *
 *  Tests therefore validate structural correctness (consistent state, correct types) and
 *  the font-registration behaviour that is fully deterministic (Liberation fonts are always
 *  bundled as classpath resources).
 */
class DocToolDetectorTest extends AnyFunSuite
{

    // ── DocToolDetector ────────────────────────────────────────────────────────

    test("DocToolDetector.status returns a consistent DocToolStatus") {
        val s = DocToolDetector.status
        // If available, path must be present; if not available, path must be absent.
        if (s.available)
        {
            assert(s.path.isDefined,    "available=true must imply path is defined")
            assert(s.path.get.nonEmpty, "path must be a non-empty string")
        }
        else
        {
            assert(s.path.isEmpty,    "available=false must imply path is absent")
            assert(s.version.isEmpty, "available=false must imply version is absent")
        }
    }

    test("DocToolDetector.status is idempotent — repeated calls return the same object") {
        val s1 = DocToolDetector.status
        val s2 = DocToolDetector.status
        assert(s1 eq s2, "status must be a cached singleton (same object reference)")
    }

    test("DocToolDetector.available matches status.available") {
        assert(DocToolDetector.available == DocToolDetector.status.available)
    }

    test("DocToolDetector.installInstructions is non-empty and mentions soffice") {
        val instructions = DocToolDetector.installInstructions
        assert(instructions.nonEmpty, "install instructions must not be empty")
        assert(instructions.contains("libreoffice") || instructions.contains("LibreOffice"),
            "install instructions must mention LibreOffice")
    }

    // ── DocFontLoader ──────────────────────────────────────────────────────────

    test("DocFontLoader.init() does not throw") {
        try {
            DocFontLoader.init()
            // Success - no exception thrown
        } catch {
            case ex: Exception => fail(s"DocFontLoader.init() threw an exception: ${ex.getMessage}")
        }
    }

    test("DocFontLoader.init() is idempotent — second call is a no-op") {
        DocFontLoader.init()
        val sizeAfterFirst = DocFontLoader.loadedFonts.size()
        DocFontLoader.init()
        val sizeAfterSecond = DocFontLoader.loadedFonts.size()
        assert(sizeAfterFirst == sizeAfterSecond,
            "second init() must not change the number of loaded fonts")
    }

    test("DocFontLoader loads all bundled Liberation fonts from classpath resources") {
        DocFontLoader.init()
        val loaded = DocFontLoader.loadedFonts

        val expectedFamilies = List(
            "Liberation Sans",
            "Liberation Sans Bold",
            "Liberation Serif",
            "Liberation Serif Bold",
            "Liberation Mono"
        )
        expectedFamilies.foreach { family =>
            assert(loaded.containsKey(family),
                s"expected '$family' to be loaded from classpath resources")
            assert(loaded.get(family).length > 0,
                s"font bytes for '$family' must not be empty")
        }
    }

    test("DocFontLoader loaded font bytes look like TTF (magic bytes 0x00 0x01 0x00 0x00 or 'true' / 'OTTO')") {
        DocFontLoader.init()
        DocFontLoader.loadedFonts.values().forEach { bytes =>
            assert(bytes.length >= 4, "font bytes must be at least 4 bytes long")
            val magic = bytes.take(4)
            val isTtf  = magic(0) == 0x00.toByte && magic(1) == 0x01.toByte
            val isTrue = magic.sameElements(Array('t', 'r', 'u', 'e').map(_.toByte))
            val isOtto = magic.sameElements(Array('O', 'T', 'T', 'O').map(_.toByte))
            assert(isTtf || isTrue || isOtto,
                s"font bytes must start with a valid TTF/OTF magic: got ${magic.map(b => f"$b%02x").mkString(" ")}")
        }
    }
}
