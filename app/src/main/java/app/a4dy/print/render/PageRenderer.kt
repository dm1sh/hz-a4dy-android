// SPDX-License-Identifier: MIT
package app.a4dy.print.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.a4dy.core.*
import app.a4dy.print.data.PageRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PageRenderer {
    /** All PDF/file/bitmap work happens off the UI thread; use one bounded page at a time. */
    suspend fun render(ref: PageRef, options: PrintOptions): RasterPage {
        val width = options.paper.widthDots; val height = options.paper.heightDots
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            target.eraseColor(Color.WHITE)
            if (ref.document.isPdf) {
                ParcelFileDescriptor.open(ref.document.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf -> pdf.openPage(ref.index).use { page ->
                        val (matrix, clip) = transform(page.width, page.height, width, height, options)
                        page.render(target, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    } }
                }
            } else {
                val source = ImageDecoder.createSource(ref.document.file)
                val image = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = min(1.0, min(3072.0 / max(info.size.width, info.size.height),
                        kotlin.math.sqrt(6_000_000.0 / (info.size.width.toDouble() * info.size.height))))
                    decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
                }
                try {
                    val (matrix, clip) = transform(image.width, image.height, width, height, options)
                    val canvas = Canvas(target); canvas.save(); canvas.clipRect(clip); canvas.concat(matrix)
                    canvas.drawBitmap(image, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)); canvas.restore()
                } finally { image.recycle() }
            }
            val coroutine = currentCoroutineContext()
            return Monochrome.convert(width, height, options,
                { y, row -> target.getPixels(row, 0, width, 0, y, width, 1) }, { coroutine.ensureActive() })
        } finally { target.recycle() }
    }
    fun preview(page: RasterPage): Bitmap {
        val full = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(page.width)
        for (y in 0 until page.height) {
            for (x in 0 until page.width) row[x] = if ((page.data[y * page.rowBytes + x / 8].toInt() and (0x80 ushr (x % 8))) != 0) Color.BLACK else Color.WHITE
            full.setPixels(row, 0, page.width, 0, y, page.width, 1)
        }
        if (page.width <= 840) return full
        val scaled = Bitmap.createScaledBitmap(full, 840, (840.0 * page.height / page.width).roundToInt(), false)
        full.recycle(); return scaled
    }
    private fun transform(sw: Int, sh: Int, width: Int, height: Int, o: PrintOptions): Pair<Matrix, Rect> {
        val crop = RectF(sw * o.cropHorizontal / 100f, sh * o.cropVertical / 100f,
            sw * (1 - o.cropHorizontal / 100f), sh * (1 - o.cropVertical / 100f))
        val rotated = o.rotation == 90 || o.rotation == 270
        val cw = if (rotated) crop.height() else crop.width(); val ch = if (rotated) crop.width() else crop.height()
        val margin = o.marginMm * 203f / 25.4f
        val usableW = width - 2 * margin; val usableH = height - 2 * margin
        val scale = if (o.layout == app.a4dy.core.Layout.FILL) max(usableW / cw, usableH / ch) else min(usableW / cw, usableH / ch)
        val matrix = Matrix().apply {
            postTranslate(-crop.centerX(), -crop.centerY()); postRotate(o.rotation.toFloat())
            postScale(scale, scale); postTranslate(width / 2f, height / 2f)
        }
        val clip = Rect(max(margin, width / 2f - cw * scale / 2).roundToInt(), max(margin, height / 2f - ch * scale / 2).roundToInt(),
            min(width - margin, width / 2f + cw * scale / 2).roundToInt(), min(height - margin, height / 2f + ch * scale / 2).roundToInt())
        return matrix to clip
    }
    fun smallTest(): RasterPage {
        val width = 640; val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textSize = 32f }
            canvas.drawText("A4DY • Bluetooth test", 16f, 48f, p)
            p.typeface = Typeface.DEFAULT; p.textSize = 22f
            canvas.drawText("203 dpi / Classic SPP / local only", 16f, 84f, p)
            canvas.drawLine(16f, 106f, 620f, 106f, p)
            for (i in 0..4) { p.color = Color.rgb(i * 63, i * 63, i * 63); canvas.drawRect(16f + i * 116f, 126f, 116f + i * 116f, 184f, p) }
            return Monochrome.convert(width, height, PrintOptions(), { y, row -> bitmap.getPixels(row, 0, width, 0, y, width, 1) })
        } finally { bitmap.recycle() }
    }
}
