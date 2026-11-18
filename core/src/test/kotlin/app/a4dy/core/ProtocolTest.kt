package app.a4dy.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class ProtocolTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    @Test fun bluetoothFramingIsNotUsbFraming() {
        assertEquals("10FFFE01", hex(DyProtocol.start)); assertEquals("10FFFE45", hex(DyProtocol.end))
        assertEquals("1B4A64", hex(DyProtocol.feed(Feed.CONTINUOUS)))
        assertEquals("1F122002", hex(DyProtocol.feed(Feed.LABEL)))
    }
    @Test fun smallPacketHasBigEndianDimensions() {
        val packet = DyProtocol.raster(RasterPage(9, 257, ByteArray(514)))
        assertEquals("1F0000020101", hex(packet.take(6).toByteArray()))
        val length = (6..9).fold(0) { value, i -> (value shl 8) or (packet[i].toInt() and 255) }
        assertEquals(packet.size - 10, length)
    }
    @Test fun retainAdlerTrailerAndRemoveOnlyZlibHeader() {
        val data = byteArrayOf(0x80.toByte(), 1, 2, 3)
        val reference = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(data) } }.toByteArray()
        val packet = DyProtocol.raster(RasterPage(8, 4, data))
        assertArrayEquals(reference.copyOfRange(2, reference.size), packet.copyOfRange(10, packet.size))
    }
    @Test fun randomRasterRoundTrips() {
        val random = Random(8203)
        for (width in listOf(1, 8, 9, 383, 1678, 1728)) for (height in listOf(1, 24, 257)) {
            val stride = (width + 7) / 8
            val bits = ByteArray(stride * height).also { random.nextBytes(it) }
            if (width % 8 != 0) for (y in 0 until height) bits[(y + 1) * stride - 1] =
                (bits[(y + 1) * stride - 1].toInt() and (0xff shl (8 - width % 8))).toByte()
            val packet = DyProtocol.raster(RasterPage(width, height, bits))
            val zlib = byteArrayOf(0x78, 0x9c.toByte()) + packet.copyOfRange(10, packet.size)
            assertArrayEquals(bits, InflaterInputStream(zlib.inputStream()).use { it.readBytes() })
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsExcessWidth() { RasterPage(1729, 1, ByteArray(217)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsShortData() { RasterPage(8, 8, ByteArray(7)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsDirtyPadding() { RasterPage(1, 1, byteArrayOf(1)) }
    @Test fun statusCodes() {
        val expected = listOf(0 to PrinterStatus.READY, 1 to PrinterStatus.BUSY, 4 to PrinterStatus.NO_PAPER,
            6 to PrinterStatus.COVER_OPEN, 8 to PrinterStatus.LOW_BATTERY, 16 to PrinterStatus.OVERHEATED)
        for ((code, status) in expected) assertEquals(status, PrinterStatus.parse(byteArrayOf(0x10, code.toByte())))
        assertEquals(PrinterStatus.UNKNOWN, PrinterStatus.parse(byteArrayOf()))
        assertEquals(PrinterStatus.UNKNOWN, PrinterStatus.parse(byteArrayOf(99)))
    }
    @Test fun ackAndErrorReplies() {
        assertEquals(PrintReply.Ack, PrintReply.parse(byteArrayOf(0xaa.toByte())))
        assertEquals(PrintReply.Ack, PrintReply.parse("OK".toByteArray()))
        assertEquals(PrintReply.Failure(-1), PrintReply.parse(byteArrayOf(0xfd.toByte())))
        assertEquals(PrintReply.Failure(6), PrintReply.parse(byteArrayOf(0xff.toByte(), 6)))
        assertEquals(PrintReply.Unknown, PrintReply.parse(byteArrayOf(0xff.toByte())))
    }
    @Test fun fragmentedAck() {
        val buffer = ReplyBuffer(); buffer.append(byteArrayOf(0x4f)); buffer.append(byteArrayOf(0x4b))
        assertEquals(PrintReply.Ack, PrintReply.parse(buffer.bytes()))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedReply() { ReplyBuffer().append(ByteArray(257)) }
    @Test fun commandBytes() {
        assertEquals("10FF100003", hex(DyProtocol.density(3)))
        assertEquals("10FF40", hex(DyProtocol.status)); assertEquals("10FF50F1", hex(DyProtocol.battery))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDensity() { DyProtocol.density(4) }
}
