package agentica.doc

import agentica.llm.{LLMProvider, LLMResponse}
import agentica.session.Message
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Paths

/**
 *  Tests for [[PDFPageRenderer]] and the Vision ingestion pipeline.
 *
 *  Uses the fixture PDF at src/test/resources/files/IT Support Analyst - India.pdf.
 *
 *  Covers:
 *  - PDFBox class initialisation (catches NoClassDefFoundError for LogManager)
 *  - AWT headless rendering (catches hangs if java.awt.headless is not set)
 *  - Full Vision pipeline via a stub LLMProvider
 */
class PDFPageRendererTest extends AnyFunSuite
{

    private val pdfResource = "/files/IT Support Analyst - India.pdf"

    private def pdfPath =
        Paths.get(getClass.getResource(pdfResource).toURI)

    /** Stub LLM that returns a fixed Markdown string for every vision call. */
    private class StubVisionProvider extends LLMProvider
    {
        val modelName: String = "stub-vision"
        override val supportsVision: Boolean = true

        override def completeVision(base64Image: String, prompt: String): String =
            "## Stub page\n\nThis is stub Markdown returned by the test double."

        def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
            throw UnsupportedOperationException("not used in vision tests")

        override def streamResponses(
            input:              List[Message],
            onToken:            String => Unit,
            previousResponseId: Option[String]
        ): LLMResponse =
            throw UnsupportedOperationException("not used in vision tests")
    }

    test("PDFPageRenderer: PDFBox initialises without NoClassDefFoundError") {
        // Directly exercises the PDFBox class-init path that was failing with
        // NoClassDefFoundError for org.apache.logging.log4j.LogManager.
        val images = PDFPageRenderer.renderToImages(pdfPath)
        assert(images.nonEmpty, "PDF must produce at least one page image")
    }

    test("PDFPageRenderer: each page image is a non-empty PNG byte array") {
        val images = PDFPageRenderer.renderToImages(pdfPath)
        images.zipWithIndex.foreach { case (bytes, i) =>
            assert(bytes.length > 0, s"page $i image must not be empty")
            // PNG magic bytes: 0x89 0x50 0x4E 0x47
            assert(bytes(0) == 0x89.toByte && bytes(1) == 0x50.toByte,
                s"page $i image must be a valid PNG (wrong magic bytes)")
        }
    }

    test("PageVisionTranscriber: produces one Markdown section per page") {
        val images   = PDFPageRenderer.renderToImages(pdfPath)
        val markdown = PageVisionTranscriber.transcribe(images, StubVisionProvider(), traceId = "test")
        val sections = markdown.split("\n\n---\n\n")
        assert(sections.length == images.size,
            s"expected ${images.size} sections (one per page), got ${sections.length}")
    }

    test("PageVisionTranscriber: stubMarkdown returns one placeholder per page") {
        val images = PDFPageRenderer.renderToImages(pdfPath)
        val stub   = PageVisionTranscriber.stubMarkdown(images.size)
        val lines  = stub.split("\n\n---\n\n")
        assert(lines.length == images.size)
        assert(lines.head.contains("vision enrichment skipped"))
    }
}
