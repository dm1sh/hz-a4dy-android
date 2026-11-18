// SPDX-License-Identifier: MIT
package app.a4dy.core

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/** Original implementation of the HyPrint DyPrinter packet format; not the CUPS/USB format. */
object DyProtocol {
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    const val CHUNK_SIZE = 1024
    val start get() = byteArrayOf(0x10, 0xff.toByte(), 0xfe.toByte(), 0x01)
    val end get() = byteArrayOf(0x10, 0xff.toByte(), 0xfe.toByte(), 0x45)
    val status get() = byteArrayOf(0x10, 0xff.toByte(), 0x40)
    val battery get() = byteArrayOf(0x10, 0xff.toByte(), 0x50, 0xf1.toByte())
    val firmware get() = byteArrayOf(0x10, 0xff.toByte(), 0x20, 0xf1.toByte())
    val queryDensity get() = byteArrayOf(0x10, 0xff.toByte(), 0x11)
    fun density(level: Int): ByteArray {
        require(level in 0..3)
        return byteArrayOf(0x10, 0xff.toByte(), 0x10, 0, level.toByte())
    }
    fun feed(mode: Feed) = if (mode == Feed.CONTINUOUS) byteArrayOf(0x1b, 0x4a, 100)
                          else byteArrayOf(0x1f, 0x12, 0x20, 2)
    fun raster(page: RasterPage): ByteArray {
        val compressed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(page.data) }
        }.toByteArray()
        // The vendor removes ONLY the two-byte zlib header, retaining Adler-32.
        val length = compressed.size - 2
        val out = ByteArrayOutputStream(length + 10)
        out.write(0x1f); out.write(0)
        fun be16(v: Int) { out.write(v ushr 8); out.write(v and 255) }
        be16(page.rowBytes); be16(page.height)
        for (shift in listOf(24, 16, 8, 0)) out.write((length ushr shift) and 255)
        out.write(compressed, 2, length)
        return out.toByteArray()
    }
    fun pagePackets(page: RasterPage, feed: Feed) = listOf(start, raster(page), feed(feed), end)
}

enum class PrinterStatus(val label: String, val canPrint: Boolean = false) {
    READY("Ready", true), BUSY("Printing"), NO_PAPER("Out of paper"), COVER_OPEN("Cover open"),
    LOW_BATTERY("Low battery"), OVERHEATED("Overheated"), UNKNOWN("Status unavailable");
    companion object {
        fun parse(reply: ByteArray): PrinterStatus = when (reply.lastOrNull()?.toInt()?.and(255)) {
            0 -> READY; 1 -> BUSY; 4 -> NO_PAPER; 6 -> COVER_OPEN; 8 -> LOW_BATTERY; 16 -> OVERHEATED; else -> UNKNOWN
        }
    }
}
sealed class PrintReply {
    data object Ack : PrintReply()
    data class Failure(val code: Int) : PrintReply()
    data object Unknown : PrintReply()
    companion object {
        fun parse(bytes: ByteArray): PrintReply = when {
            bytes.contentEquals(byteArrayOf(0xaa.toByte())) || bytes.contentEquals(byteArrayOf(0x4f, 0x4b)) -> Ack
            bytes.firstOrNull() == 0xfd.toByte() -> Failure(-1)
            bytes.firstOrNull() == 0xff.toByte() && bytes.size >= 2 -> Failure(bytes[1].toInt() and 255)
            else -> Unknown
        }
    }
}

/** Accumulate split Bluetooth reads without assuming that read() == one reply. */
class ReplyBuffer(private val limit: Int = 256) {
    private val out = ByteArrayOutputStream()
    fun append(bytes: ByteArray) {
        require(out.size() + bytes.size <= limit) { "Oversized printer reply." }
        out.write(bytes)
    }
    fun bytes(): ByteArray = out.toByteArray()
}
