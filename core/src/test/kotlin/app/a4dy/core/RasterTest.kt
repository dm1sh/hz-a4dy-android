package app.a4dy.core

import org.junit.Assert.*
import org.junit.Test

class RasterTest {
    private fun raster(pixels: IntArray, options: PrintOptions = PrintOptions(halftone = Halftone.THRESHOLD)) =
        Monochrome.convert(pixels.size, 1, options, { _, row -> pixels.copyInto(row) })
    @Test fun bitOrderAndRightPadding() {
        val page = raster(intArrayOf(0xff000000.toInt(), -1, -1, -1, -1, -1, -1, -1, 0xff000000.toInt()))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte()), page.data)
    }
    @Test fun alphaIsCompositedOnWhite() {
        assertArrayEquals(byteArrayOf(0), raster(intArrayOf(0x00000000, 0x40ffffff, -1)).data)
    }
    @Test fun inclusiveThreshold() {
        val px = intArrayOf(0xff7f7f7f.toInt(), 0xff808080.toInt(), 0xff818181.toInt())
        assertArrayEquals(byteArrayOf(0xc0.toByte()), raster(px).data)
    }
    @Test fun mirrorHasWhitePadding() {
        val px = IntArray(9) { -1 }; px[0] = 0xff000000.toInt()
        assertArrayEquals(byteArrayOf(0, 0x80.toByte()), raster(px, PrintOptions(mirror = true, halftone = Halftone.THRESHOLD)).data)
    }
    @Test fun negativeDoesNotBlackenPadding() {
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0x80.toByte()), raster(IntArray(9) { -1 }, PrintOptions(negative = true)).data)
    }
    @Test fun diffusionPreservesSolidBlackAndWhite() {
        assertTrue(raster(IntArray(32) { -1 }, PrintOptions()).data.all { it == 0.toByte() })
        assertTrue(raster(IntArray(32) { 0xff000000.toInt() }, PrintOptions()).data.all { it == 0xff.toByte() })
    }
    @Test fun diffusionMakesMidGrayDots() {
        val page = Monochrome.convert(64, 64, PrintOptions(), { _, row -> row.fill(0xff808080.toInt()) })
        val black = page.data.sumOf { Integer.bitCount(it.toInt() and 255) }
        assertTrue(black in 1800..2300)
    }
    @Test fun cancellationIsCheckedEveryRow() {
        var rows = 0
        try {
            Monochrome.convert(8, 20, PrintOptions(), { _, row -> row.fill(-1) }, { if (++rows == 4) error("cancel") })
            fail("Expected cancellation")
        } catch (_: IllegalStateException) { assertEquals(4, rows) }
    }
    @Test fun pageDimensions() { assertEquals(1678, Paper.A4.widthDots); assertEquals(2374, Paper.A4.heightDots) }
    @Test fun selectionAndCollation() {
        assertEquals(listOf(0, 1, 2, 4), PageSelection.parse("1-3,5,2", 5))
        assertEquals(listOf(0, 1, 0, 1), PageSelection.expand(listOf(0, 1), 2, true))
        assertEquals(listOf(0, 0, 1, 1), PageSelection.expand(listOf(0, 1), 2, false))
    }
    @Test(expected = IllegalArgumentException::class) fun selectionOutOfRange() { PageSelection.parse("1-9", 5) }
    @Test(expected = IllegalArgumentException::class) fun reversedRange() { PageSelection.parse("3-1", 5) }
    @Test(expected = IllegalArgumentException::class) fun malformedRange() { PageSelection.parse("1,,2", 5) }
    @Test(expected = IllegalArgumentException::class) fun zeroPage() { PageSelection.parse("0", 5) }
    @Test(expected = IllegalArgumentException::class) fun jobLimit() { PageSelection.expand((0..499).toList(), 99, true) }
    @Test(expected = IllegalArgumentException::class) fun invalidRotation() { PrintOptions(rotation = 45) }
}
