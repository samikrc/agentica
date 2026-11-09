package agentica.misctests

import agentica.doc.PDFPageRenderer
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files

class VLMCompareOptionsTest extends AnyFunSuite
{
    test("fixed mode follows production defaults") {
        val options = VLMCompareTest.parseOptions(Nil)
        assert(!options.sweep)
        assert(options.dpis == List(PDFPageRenderer.RenderDPI.toInt))
        assert(options.maxDimensions == List(PDFPageRenderer.MaxImageDimension))
    }

    test("fixed overrides and sweep grids remain distinct") {
        val fixed = VLMCompareTest.parseOptions(List("--dpi", "100", "--max-dimension", "1650"))
        assert(fixed.dpis == List(100) && fixed.maxDimensions == List(1650))
        val sweep = VLMCompareTest.parseOptions(List("--sweep", "--dpi-values", "150,50", "--max-dimensions", "1200,1650"))
        assert(sweep.sweep && sweep.dpis == List(150, 50) && sweep.maxDimensions == List(1200, 1650))
        assert(VLMCompareTest.parseOptions(List("--sweep")).dpis == List(50, 100, 150))
    }

    test("invalid options fail before model access") {
        List(
            List("--unknown"), List("--dpi"), List("--dpi", "0"), List("--dpi", "-1"),
            List("--dpi", "nan"), List("--dpi", "50,100"), List("--dpi", "50", "--dpi", "100"),
            List("--sweep", "--dpi", "150"), List("--max-dimensions", "1200"),
            List("--sweep", "--dpi-values", "50,50"), List("--sweep", "--dpi-values", ""),
            List("--sweep", "--sweep")
        ).foreach(args => intercept[IllegalArgumentException](VLMCompareTest.parseOptions(args)))
    }

    test("resizing preserves aspect ratio and never upscales") {
        val image = new java.awt.image.BufferedImage(200, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val output = new java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "PNG", output)
        val bytes = output.toByteArray
        assert(PDFPageRenderer.resizeToMaxDimension(bytes, 500).sameElements(bytes))
        val resized = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
            PDFPageRenderer.resizeToMaxDimension(bytes, 100)))
        assert(resized.getWidth == 50 && resized.getHeight == 100)
    }

    test("default preprocessing caps large pages and leaves smaller pages unchanged") {
        val pages = List((10, 1300), (400, 20)).map { case (w, h) =>
            val output = new java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "PNG", output)
            output.toByteArray
        }
        val prepared = VLMCompareTest.prepareImages(pages)
        assert(PDFPageRenderer.imageLongestSide(prepared.head) == PDFPageRenderer.MaxImageDimension)
        assert(prepared(1).sameElements(pages(1)))
    }

    test("reuse requires unchanged inputs in both configurations across every page") {
        val downscaled = SweepResult("doc", "model", 100, 1024, false,
            100, 20, 1000L, 500L, 1.0, 1.0, 1, 1, 1.0, "", status = "completed")
        assert(VLMCompareTest.reusableResult(Some(downscaled), List(1170), 1200).isEmpty)
        val original = downscaled.copy(maxImageDimension = 1200)
        assert(VLMCompareTest.reusableResult(Some(original), List(1170), 1400).contains(original))
        assert(VLMCompareTest.reusableResult(Some(original), List(1170), 1024).isEmpty)
        assert(VLMCompareTest.reusableResult(Some(original), List(900, 1500), 1650).isEmpty)
        assert(VLMCompareTest.reusableResult(Some(original), Nil, 1650).isEmpty)
        assert(VLMCompareTest.reusableResult(Some(original.copy(status = "partial-error", failedPages = 1)), List(1170), 1400).isEmpty)
    }

    test("structured results preserve artifacts and distinguish reused timing from measurements") {
        val directory = Files.createTempDirectory("vlm-options-test-")
        try
        {
            val result = SweepResult("doc", "model", 150, 1200, false,
                100, 20, 1000L, 500L, 1.0, 1.0, 1, 1, 1.0, "",
                status = "completed", model = "vision-model", pageDimensions = List((600, 1200)),
                markdownPath = Some("document.md"))
            val reused = result.copy(maxImageDimension = 1650, status = "reused", reusedFrom = Some(1200))
            VLMCompareTest.saveResults(directory, VLMCompareTest.parseOptions(List("--sweep")), List(result, reused))
            val json = ujson.read(Files.readString(directory.resolve("results.json")))
            assert(json("format").str == "PNG")
            assert(json("results")(0)("transcriptionWallTimeMs").num == 1000)
            assert(json("results")(0)("pageDimensions")(0)("height").num == 1200)
            assert(json("results")(1)("transcriptionWallTimeMs") == ujson.Null)
            assert(json("results")(1)("reusedFromMaxDimension").num == 1200)
            assert(json("results")(1)("markdownPath").str == "document.md")
        }
        finally
        {
            Files.deleteIfExists(directory.resolve("results.json"))
            Files.delete(directory)
        }
    }
}
