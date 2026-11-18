// SPDX-License-Identifier: MIT
package app.a4dy.print.bluetooth

import app.a4dy.core.PrinterStatus
import app.a4dy.print.data.SavedPrinter

/** One serialized owner. Tests use an in-memory link; shipping code uses Classic RFCOMM. */
interface PrinterLink {
    val connected: Boolean
    val address: String?
    suspend fun connect(printer: SavedPrinter, allowInsecure: Boolean)
    fun close()
    suspend fun exchange(command: ByteArray, timeoutMs: Long = 2500): ByteArray
    suspend fun status(): PrinterStatus
    suspend fun ready(maxWaitMs: Long = 90_000): Boolean
    suspend fun setDensity(level: Int)
    suspend fun sendPage(packets: List<ByteArray>, progress: (Int, Int) -> Unit): Boolean
}
