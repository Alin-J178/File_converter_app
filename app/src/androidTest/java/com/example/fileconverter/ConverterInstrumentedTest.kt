package com.example.fileconverter

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for all converter formats.
 * Test files must be pushed to /sdcard/Download/FileConverter_test/ before running.
 */
@RunWith(AndroidJUnit4::class)
class ConverterInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun getFileUri(filename: String): Uri {
        val file = File("/storage/emulated/0/Download/FileConverter_test/$filename")
        assertTrue("Test file $filename must exist on device", file.exists())
        return Uri.fromFile(file)
    }

    // ---- DocToDocx tests ----

    @Test
    fun testTxtToDocx() {
        val uri = getFileUri("test.txt")
        val outUri = DocToDocx.convert(context, uri, "test_txt_to_docx.docx")
        assertNotNull("TXT→DOCX should produce output", outUri)
        assertTrue("Output URI should not be empty", outUri != Uri.EMPTY)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ TXT → DOCX: success, output size = $size bytes")
    }

    @Test
    fun testRtfToDocx() {
        val uri = getFileUri("test.rtf")
        val outUri = DocToDocx.convert(context, uri, "test_rtf_to_docx.docx")
        assertNotNull("RTF→DOCX should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ RTF → DOCX: success, output size = $size bytes")
    }

    @Test
    fun testMdToDocx() {
        val uri = getFileUri("test.md")
        val outUri = DocToDocx.convert(context, uri, "test_md_to_docx.docx")
        assertNotNull("MD→DOCX should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ MD → DOCX: success, output size = $size bytes")
    }

    @Test
    fun testHtmlToDocx() {
        val uri = getFileUri("test.html")
        val outUri = DocToDocx.convert(context, uri, "test_html_to_docx.docx")
        assertNotNull("HTML→DOCX should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ HTML → DOCX: success, output size = $size bytes")
    }

    // ---- TextToPdf tests ----

    @Test
    fun testTxtToPdf() {
        val uri = getFileUri("test.txt")
        val outUri = TxtToPdf.convert(context, uri, "test_txt_to_pdf.pdf")
        assertNotNull("TXT→PDF should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ TXT → PDF: success, output size = $size bytes")
    }

    @Test
    fun testRtfToPdf() {
        val uri = getFileUri("test.rtf")
        val outUri = RtfToPdf.convert(context, uri, "test_rtf_to_pdf.pdf")
        assertNotNull("RTF→PDF should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ RTF → PDF: success, output size = $size bytes")
    }

    @Test
    fun testMdToPdf() {
        val uri = getFileUri("test.md")
        val outUri = MdToPdf.convert(context, uri, "test_md_to_pdf.pdf")
        assertNotNull("MD→PDF should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ MD → PDF: success, output size = $size bytes")
    }

    @Test
    fun testHtmlToPdf() {
        val uri = getFileUri("test.html")
        val outUri = HtmlToPdf.convert(context, uri, "test_html_to_pdf.pdf")
        assertNotNull("HTML→PDF should produce output", outUri)
        val size = ImageConverter.querySize(context, outUri)
        assertTrue("Output file should have content (size=$size)", size > 0)
        println("✅ HTML → PDF: success, output size = $size bytes")
    }

    // ---- Thumbnail rendering tests ----

    @Test
    fun testTxtThumbnail() {
        val uri = getFileUri("test.txt")
        val bmp = ImageConverter.renderThumbnail(context, uri, "test.txt", maxDim = 200)
        assertNotNull("TXT thumbnail should not be null", bmp)
        assertTrue("TXT thumbnail should have pixels", bmp!!.width > 0 && bmp.height > 0)
        println("✅ TXT thumbnail: ${bmp.width}x${bmp.height}")
        bmp.recycle()
    }

    @Test
    fun testRtfThumbnail() {
        val uri = getFileUri("test.rtf")
        val bmp = ImageConverter.renderThumbnail(context, uri, "test.rtf", maxDim = 200)
        assertNotNull("RTF thumbnail should not be null", bmp)
        println("✅ RTF thumbnail: ${bmp!!.width}x${bmp.height}")
        bmp.recycle()
    }

    @Test
    fun testMdThumbnail() {
        val uri = getFileUri("test.md")
        val bmp = ImageConverter.renderThumbnail(context, uri, "test.md", maxDim = 200)
        assertNotNull("MD thumbnail should not be null", bmp)
        println("✅ MD thumbnail: ${bmp!!.width}x${bmp.height}")
        bmp.recycle()
    }

    @Test
    fun testHtmlThumbnail() {
        val uri = getFileUri("test.html")
        val bmp = ImageConverter.renderThumbnail(context, uri, "test.html", maxDim = 200)
        assertNotNull("HTML thumbnail should not be null", bmp)
        println("✅ HTML thumbnail: ${bmp!!.width}x${bmp.height}")
        bmp.recycle()
    }

    // ---- OutputFormat enum completeness ----

    @Test
    fun testOutputFormatEnumHasDocx() {
        val format = OutputFormat.DOCX
        assertEquals("DOCX", format.label)
        assertEquals("docx", format.extension)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", format.mimeType)
        assertFalse("DOCX is not lossy", format.lossy)
        println("✅ OutputFormat.DOCX enum entry verified")
    }
}
