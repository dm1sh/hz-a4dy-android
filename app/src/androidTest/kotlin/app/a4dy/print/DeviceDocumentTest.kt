// SPDX-License-Identifier: MIT
package app.a4dy.print

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.a4dy.core.*
import app.a4dy.print.data.*
import app.a4dy.print.render.PageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DeviceDocumentTest {
    private lateinit var context: Context
    private lateinit var documents: Documents
    @Before fun setup() { context = ApplicationProvider.getApplicationContext<A4Application>(); documents = Documents(context); documents.clearCache() }
    private suspend fun image(): DocumentItem {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawRect(0f, 0f, 50f, 100f, Paint().apply { color = Color.BLACK })
        val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
        return documents.copy(bytes.toByteArray().inputStream(), "half.png")
    }
    private fun black(page: RasterPage, x: Int, y: Int) = page.data[y * page.rowBytes + x / 8].toInt() and (0x80 ushr (x % 8)) != 0
    @Test fun imageImportSniffsContentNotExtensionAndRenders() = runBlocking {
        val item = image(); assertEquals(1, item.pages); assertFalse(item.isPdf)
        val raster = PageRenderer.render(PageRef(item, 0), PrintOptions(halftone = Halftone.THRESHOLD, marginMm = 0))
        assertEquals(1678, raster.width); assertEquals(2374, raster.height)
        assertTrue(black(raster, 300, raster.height / 2)); assertFalse(black(raster, 1300, raster.height / 2))
        assertFalse(black(raster, 300, 5)) // Fit leaves vertical white space for this square image.
    }
    @Test fun mirrorAndRotationTransformOutput() = runBlocking {
        val ref = PageRef(image(), 0)
        val options = PrintOptions(halftone = Halftone.THRESHOLD, marginMm = 0)
        val mirrored = PageRenderer.render(ref, options.copy(mirror = true))
        assertFalse(black(mirrored, 300, mirrored.height / 2)); assertTrue(black(mirrored, 1300, mirrored.height / 2))
        val rotated = PageRenderer.render(ref, options.copy(rotation = 90))
        assertTrue(black(rotated, 800, 700)); assertFalse(black(rotated, 800, 1700))
    }
    @Test fun fillAndMarginsAreVisible() = runBlocking {
        val ref = PageRef(image(), 0)
        val fill = PageRenderer.render(ref, PrintOptions(halftone = Halftone.THRESHOLD, layout = Layout.FILL, marginMm = 10))
        assertFalse(black(fill, 5, 1100)); assertTrue(black(fill, 200, 150)); assertFalse(black(fill, 1600, 1200))
    }
    @Test fun textCreatesRealMultipagePdf() = runBlocking {
        val document = documents.text((1..110).joinToString("\n") { "Line $it — independent local printing." })
        assertTrue(document.isPdf); assertTrue(document.pages >= 2)
        val header = document.file.inputStream().use { it.readNBytes(5).toString(Charsets.US_ASCII) }
        assertEquals("%PDF-", header)
        val copied = documents.copy(document.file.inputStream(), "note.renamed")
        assertEquals(document.pages, copied.pages); assertTrue(copied.isPdf)
        val raster = PageRenderer.render(PageRef(copied, 0), PrintOptions())
        assertTrue(raster.data.any { it.toInt() != 0 })
    }
    @Test fun pdfPreviewHasBoundedSize() = runBlocking {
        val document = documents.text("Bluetooth printer test 12345.")
        val raster = PageRenderer.render(PageRef(document, 0), PrintOptions())
        val preview = PageRenderer.preview(raster)
        assertEquals(840, preview.width); assertEquals(1188, preview.height); preview.recycle()
    }
    @Test fun invalidAndEmptyInputsLeaveNoCachedFile() = runBlocking {
        for (data in listOf(byteArrayOf(), "not a document".toByteArray(), "%PDF-broken".toByteArray())) {
            assertTrue(runCatching { documents.copy(data.inputStream(), "broken") }.isFailure)
        }
        assertEquals(0, java.io.File(context.cacheDir, "documents").listFiles()!!.size)
    }
    @Test fun rejectsFileAndNetworkUris() = runBlocking {
        for (uri in listOf("file:///etc/passwd", "https://example.invalid/file.pdf")) assertTrue(runCatching { documents.import(Uri.parse(uri)) }.isFailure)
    }
    @Test fun rejectsEmptyAndOversizedNotes() = runBlocking {
        for (text in listOf("  ", "x".repeat(100_001))) assertTrue(runCatching { documents.text(text) }.isFailure)
    }
    @Test fun clearingImportsDoesNotDeleteActiveSpoolPdf() = runBlocking {
        val document = documents.text("Keep this system job.")
        val spooled = document.file.inputStream().use { documents.copy(it, "spooled.pdf", spool = true) }
        documents.clearCache(); assertFalse(document.file.exists()); assertTrue(spooled.file.exists())
        documents.clearOrphanedSpool(); assertFalse(spooled.file.exists())
    }
    @Test fun smallTestHasLowBoundedDimensionsAndPreview() {
        val raster = PageRenderer.smallTest(); assertEquals(640, raster.width); assertEquals(200, raster.height)
        assertTrue(raster.data.any { it.toInt() != 0 }); assertTrue(raster.data.any { it.toInt() == 0 })
        val preview = PageRenderer.preview(raster); assertEquals(640, preview.width); preview.recycle()
    }
}
