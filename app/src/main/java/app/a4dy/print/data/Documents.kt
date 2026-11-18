// SPDX-License-Identifier: MIT
package app.a4dy.print.data

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.util.UUID

data class DocumentItem(val file: File, val name: String, val isPdf: Boolean, val pages: Int)
data class PageRef(val document: DocumentItem, val index: Int)
data class DocumentSet(val documents: List<DocumentItem>) {
    val pages: List<PageRef> = documents.flatMap { d -> List(d.pages) { PageRef(d, it) } }
    val title: String get() = if (documents.size == 1) documents.first().name else "${documents.size} documents"
}

class Documents(private val context: Context) {
    private val directory get() = File(context.cacheDir, "documents").also { it.mkdirs() }
    // Kept separate: clearing imports must not unlink an active Android system print job.
    private val spoolDirectory get() = File(context.cacheDir, "spool").also { it.mkdirs() }
    companion object { const val MAX_BYTES = 100L * 1024 * 1024; const val CACHE_BYTES = 250L * 1024 * 1024 }
    suspend fun import(uri: Uri): DocumentItem {
        require(uri.scheme == "content") { "Use Android’s file picker or share a content URI." }
        var name = "Document"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n).take(150)
                val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !cursor.isNull(s)) require(cursor.getLong(s) <= MAX_BYTES) { "Files are limited to 100 MB." }
            }
        }
        val source = context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected file.")
        return source.use { copy(it, name) }
    }
    suspend fun copy(input: InputStream, name: String, spool: Boolean = false): DocumentItem {
        val folder = if (spool) spoolDirectory else directory
        val existing = folder.listFiles()?.sumOf { it.length() } ?: 0
        require(existing < CACHE_BYTES) { "Clear imported files in Settings before adding more." }
        val file = File(folder, UUID.randomUUID().toString())
        try {
            var size = 0L; val buffer = ByteArray(32768)
            file.outputStream().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    size += count
                    require(size <= MAX_BYTES) { "Files are limited to 100 MB." }
                    require(existing + size <= CACHE_BYTES) { "The private document cache is full. Clear imported files in Settings." }
                    out.write(buffer, 0, count)
                }
            }
            require(size > 0) { "The selected file is empty." }
            val header = ByteArray(5); file.inputStream().use { it.read(header) }
            val pdf = header.contentEquals("%PDF-".toByteArray())
            val count = if (pdf) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
            else {
                require(!spool) { "Android supplied a non-PDF print document." }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a PDF or a supported image (JPEG, PNG, WebP, HEIF). Office files must first be saved as PDF." }
                1
            }
            require(count in 1..500) { "Documents must contain 1–500 pages." }
            return DocumentItem(file, name.filterNot { it.isISOControl() }.take(150).ifBlank { "Document" }, pdf, count)
        } catch (e: Exception) { file.delete(); throw e }
    }
    suspend fun text(text: String): DocumentItem {
        require(text.isNotBlank() && text.length <= 100_000) { "Enter 1–100,000 characters." }
        require((directory.listFiles()?.sumOf { it.length() } ?: 0) < CACHE_BYTES - 5 * 1024 * 1024) { "Clear imported files in Settings before adding more." }
        val file = File(directory, UUID.randomUUID().toString())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 523)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).setIncludePad(true).build()
        var start = 0; var pageNumber = 0
        val pdf = PdfDocument()
        try {
            while (start < layout.lineCount) {
                currentCoroutineContext().ensureActive()
                require(pageNumber < 500) { "Text is too long." }
                val top = layout.getLineTop(start)
                var end = start + 1
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= 769) end++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save(); page.canvas.clipRect(36, 36, 559, 805)
                page.canvas.translate(36f, 36f - top); layout.draw(page.canvas); page.canvas.restore()
                pdf.finishPage(page); start = end
            }
            file.outputStream().use { pdf.writeTo(it) }
            return DocumentItem(file, "Text note.pdf", true, pageNumber)
        } catch (e: Exception) { file.delete(); throw e }
        finally { pdf.close() }
    }
    fun clearCache() { directory.listFiles()?.forEach { it.delete() } }
    /** Called once when this process starts, before any request can own a temporary spool file. */
    fun clearOrphanedSpool() { spoolDirectory.listFiles()?.forEach { it.delete() } }
}
