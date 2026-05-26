package agentica.misctests

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument

/**
 *  Standalone smoke-test: loads a hardcoded PDF and prints the page count.
 *  Run with: mvn test -pl backend -Dtest=PDFLoadTest -Dsurefire.failIfNoSpecifiedTests=false
 *  or directly via scalatest runner.
 */
object PDFLoadTest
{
    val PdfPath: String =
        "/home/samik/git/myprojects/agentica/examples/pdf-reading/IT Support Analyst - India.pdf"

    def main(args: Array[String]): Unit =
    {
        println(s"java.awt.headless = ${System.getProperty("java.awt.headless")}")
        println(s"log4j-api on classpath: ${classOf[org.apache.logging.log4j.LogManager].getProtectionDomain.getCodeSource.getLocation}")
        println(s"Loading PDF: $PdfPath")

        val document: PDDocument = Loader.loadPDF(new java.io.File(PdfPath))
        try
        {
            val pageCount = document.getNumberOfPages
            println(s"SUCCESS — page count: $pageCount")
        }
        finally
        {
            document.close()
        }
    }
}
