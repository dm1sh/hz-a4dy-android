// SPDX-License-Identifier: MIT
package app.a4dy.core

/** Printer-native, row-major, MSB-left data; one = black. Unused right bits are white. */
data class RasterPage(val width: Int, val height: Int, val data: ByteArray) {
    val rowBytes: Int get() = (width + 7) / 8
    init {
        require(width in 1..1728 && height in 1..20000) { "Raster exceeds the 203 dpi print-head limits." }
        require(data.size == ((width + 7) / 8) * height) { "Invalid raster stride or length." }
        if (width % 8 != 0) {
            val mask = (1 shl (8 - width % 8)) - 1
            require((0 until height).all { (data[(it + 1) * rowBytes - 1].toInt() and mask) == 0 }) { "Padding must be white." }
        }
    }
}

object Monochrome {
    fun convert(width: Int, height: Int, options: PrintOptions,
                readRow: (Int, IntArray) -> Unit, checkCancelled: () -> Unit = {}): RasterPage {
        require(width in 1..1728 && height in 1..20000)
        val stride = (width + 7) / 8
        val result = ByteArray(stride * height)
        val pixels = IntArray(width)
        var errors = IntArray(width + 2); var next = IntArray(width + 2)
        for (y in 0 until height) {
            checkCancelled()
            readRow(y, pixels)
            next.fill(0)
            for (x in 0 until width) {
                val argb = pixels[x]; val alpha = argb ushr 24
                fun composite(c: Int) = (c * alpha + 255 * (255 - alpha) + 127) / 255
                val r = composite((argb ushr 16) and 255)
                val g = composite((argb ushr 8) and 255)
                val b = composite(argb and 255)
                val gray = (77 * r + 150 * g + 29 * b + 128) shr 8
                val adjusted = ((gray - 128) * options.contrast / 100 + 128 + options.brightness).coerceIn(0, 255)
                var black: Boolean
                if (options.halftone == Halftone.DIFFUSION) {
                    val value = (adjusted * 16 + errors[x + 1]).coerceIn(0, 4080)
                    black = value <= options.threshold * 16
                    val error = value - if (black) 0 else 4080
                    errors[x + 2] += error * 7 / 16
                    next[x] += error * 3 / 16
                    next[x + 1] += error * 5 / 16
                    next[x + 2] += error / 16
                } else black = adjusted <= options.threshold
                if (options.negative) black = !black
                if (black) {
                    val dx = if (options.mirror) width - 1 - x else x
                    val pos = y * stride + dx / 8
                    result[pos] = (result[pos].toInt() or (0x80 ushr (dx % 8))).toByte()
                }
            }
            val swap = errors; errors = next; next = swap
        }
        return RasterPage(width, height, result)
    }
}
