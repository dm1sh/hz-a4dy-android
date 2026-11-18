// SPDX-License-Identifier: MIT
package app.a4dy.print.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import app.a4dy.core.*
import app.a4dy.print.data.EventLog
import app.a4dy.print.data.SavedPrinter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single owner, one request at a time. Bluetooth is a stream, not a message transport. */
class SppSession(private val context: Context, private val log: EventLog, private val disconnected: () -> Unit) : PrinterLink {
    @Volatile private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    private var replies = Channel<ByteArray>(64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override var address: String? = null; private set
    override val connected get() = socket?.isConnected == true

    override suspend fun connect(printer: SavedPrinter, allowInsecure: Boolean) = withContext(Dispatchers.IO) {
        close()
        check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "Grant Nearby devices permission in the app first." }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: error("This device has no Bluetooth adapter.")
        check(adapter.isEnabled) { "Turn on Bluetooth first." }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) adapter.cancelDiscovery()
        val device = adapter.getRemoteDevice(printer.address)
        var failure: Exception? = null
        for (insecure in if (allowInsecure) listOf(false, true) else listOf(false)) {
            currentCoroutineContext().ensureActive()
            val candidate = if (insecure) device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(DyProtocol.SPP_UUID))
                            else device.createRfcommSocketToServiceRecord(UUID.fromString(DyProtocol.SPP_UUID))
            socket = candidate
            try {
                log.add("Connecting using ${if (insecure) "insecure fallback" else "secure"} Classic SPP.")
                withTimeout(12_000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { runCatching { candidate.close() } }
                        Thread({
                            try {
                                candidate.connect()
                                if (continuation.isActive) continuation.resume(Unit) else candidate.close()
                            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                        }, "A4-SPP-connect").apply { isDaemon = true; start() }
                    }
                }
                val incoming = Channel<ByteArray>(64)
                replies = incoming; address = printer.address
                reader = scope.launch {
                    try {
                        val input = candidate.inputStream; val buffer = ByteArray(256)
                        while (isActive && socket === candidate) {
                            val count = input.read(buffer)
                            if (count < 0) throw IOException("Bluetooth connection closed.")
                            if (count > 0) {
                                if (!incoming.trySend(buffer.copyOf(count)).isSuccess) throw IOException("Printer sent too much unrequested data.")
                            }
                        }
                    } catch (e: Exception) {
                        if (socket === candidate) { log.add("Bluetooth link closed."); close(); disconnected() }
                    }
                }
                log.add("SPP connected. No print data sent.")
                return@withContext
            } catch (e: CancellationException) {
                candidate.close()
                // A connection timeout may use the explicitly enabled compatibility fallback.
                if (e !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw e
                failure = e
            } catch (e: Exception) { failure = e; runCatching { candidate.close() } }
        }
        close()
        throw IOException("Could not connect using Classic SPP. Pair the printer in Android Bluetooth settings, close the vendor app, then try again. ${failure?.javaClass?.simpleName.orEmpty()}")
    }
    override fun close() {
        val previous = socket; socket = null; address = null
        runCatching { previous?.close() }; reader?.cancel(); reader = null
        replies.close()
    }
    private fun drain() { while (replies.tryReceive().isSuccess) { /* no outstanding transaction */ } }
    override suspend fun exchange(command: ByteArray, timeoutMs: Long): ByteArray {
        drain(); write(listOf(command)) { _, _ -> }
        return readReply(timeoutMs)
    }
    private suspend fun readReply(timeoutMs: Long): ByteArray = ReplyCollector.collect(replies, timeoutMs)
    override suspend fun status(): PrinterStatus {
        val bytes = exchange(DyProtocol.status)
        val status = PrinterStatus.parse(bytes)
        log.add("Status: ${status.name} (${bytes.size} reply bytes).")
        return status
    }
    override suspend fun ready(maxWaitMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        var missing = 0
        do {
            currentCoroutineContext().ensureActive()
            when (val status = status()) {
                PrinterStatus.READY -> return true
                PrinterStatus.BUSY -> { missing = 0; delay(750) }
                PrinterStatus.UNKNOWN -> { if (++missing >= 3) return false; delay(250) }
                else -> throw IOException("${status.label}. Fix the printer condition before retrying.")
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }
    override suspend fun setDensity(level: Int) {
        val reply = PrintReply.parse(exchange(DyProtocol.density(level), 1500))
        if (reply is PrintReply.Failure) throw IOException("Printer rejected density (code ${reply.code}).")
        log.add("Density $level: ${if (reply is PrintReply.Ack) "acknowledged" else "no recognized acknowledgement"}.")
    }
    override suspend fun sendPage(packets: List<ByteArray>, progress: (Int, Int) -> Unit): Boolean {
        drain()
        write(packets, progress)
        val reply = PrintReply.parse(readReply(2500))
        if (reply is PrintReply.Failure) throw IOException("Printer reported an error (code ${reply.code}). The page may be partly printed; do not automatically retry.")
        log.add("Page data transmitted; reply ${reply.javaClass.simpleName}.")
        return ready(120_000)
    }
    private suspend fun write(packets: List<ByteArray>, progress: (Int, Int) -> Unit) = coroutineScope {
        val current = socket ?: throw IOException("Printer is not connected.")
        val watchdog = launch(Dispatchers.IO) { delay(90_000); log.add("Write deadline reached; closing socket."); runCatching { current.close() } }
        try {
            val output = current.outputStream; val total = packets.sumOf { it.size }; var sent = 0
            for (packet in packets) {
                var offset = 0
                while (offset < packet.size) {
                    ensureActive(); delay(1)
                    val count = minOf(DyProtocol.CHUNK_SIZE, packet.size - offset)
                    output.write(packet, offset, count); offset += count; sent += count
                    progress(sent, total)
                }
            }
            output.flush()
        } finally { watchdog.cancel() }
    }
}
