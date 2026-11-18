// SPDX-License-Identifier: MIT
package app.a4dy.print

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import app.a4dy.core.*
import app.a4dy.print.bluetooth.SppSession
import app.a4dy.print.bluetooth.PrinterLink
import app.a4dy.print.data.*
import app.a4dy.print.render.PageRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class A4Application : Application() {
    lateinit var runtime: AppRuntime; private set
    override fun onCreate() { super.onCreate(); runtime = AppRuntime(this); runtime.documents.clearOrphanedSpool() }
}
val Context.runtime: AppRuntime get() = (applicationContext as A4Application).runtime

data class LinkState(val connected: Boolean = false, val connecting: Boolean = false, val status: String = "Not connected", val batteryCode: Int? = null, val firmware: String = "")
data class JobProgress(val id: String = "", val title: String = "", val message: String = "", val fraction: Float = 0f,
                       val running: Boolean = false, val result: String = "", val sentPages: Int = 0, val totalPages: Int = 0)
data class PrintRequest(
    val set: DocumentSet?, val options: PrintOptions, val selection: List<Int>, val copies: Int = 1,
    val collate: Boolean = true, val smallTest: Boolean = false, val printer: SavedPrinter? = null,
    val id: String = UUID.randomUUID().toString(), val title: String = set?.title ?: "Small test strip"
) {
    init {
        require(smallTest || set != null) { "Choose a document first." }
        require(selection.isNotEmpty() && selection.all { it in 0 until (set?.pages?.size ?: 1) }) { "Invalid document page selection." }
        PageSelection.expand(selection, copies, collate)
    }
}

class AppRuntime(
    private val context: Context,
    transport: PrinterLink? = null,
    private val rasterize: suspend (PageRef, PrintOptions) -> RasterPage = PageRenderer::render
) {
    val store = AppStore(context)
    val log = EventLog()
    val documents = Documents(context)
    val pending = ConcurrentHashMap<String, PrintRequest>()
    private val serial = Mutex()
    private val _link = MutableStateFlow(LinkState())
    val link = _link.asStateFlow()
    private val _progress = MutableStateFlow(JobProgress())
    val progress = _progress.asStateFlow()
    private val session: PrinterLink = transport ?: SppSession(context, log) { _link.value = LinkState(status = "Connection closed") }
    @Volatile private var activeId: String? = null
    @Volatile private var activeJob: Job? = null
    val busy get() = _progress.value.running

    suspend fun connect() = serial.withLock {
        val printer = store.printer.value ?: error("Select a Bluetooth printer first.")
        try {
            _link.value = LinkState(connecting = true, status = "Connecting…")
            withContext(Dispatchers.IO) {
                session.connect(printer, store.insecure.value)
                val status = session.status()
                val battery = session.exchange(DyProtocol.battery).let { if (it.size == 2) it[1].toInt() and 255 else null }
                val firmware = session.exchange(DyProtocol.firmware).toString(charset("GB2312")).filter { !it.isISOControl() }.take(80)
                _link.value = LinkState(connected = session.connected, status = status.label, batteryCode = battery, firmware = firmware)
            }
        } catch (e: Exception) { session.close(); _link.value = LinkState(status = e.message ?: "Connection failed"); throw e }
    }
    fun disconnect() { session.close(); _link.value = LinkState() }
    fun dismissProgress() { if (!_progress.value.running) _progress.value = JobProgress() }
    fun rejectBeforeStart(request: PrintRequest, message: String) {
        if (!busy) _progress.value = JobProgress(request.id, request.title, message, result = "FAILED")
        store.record(HistoryEntry(request.title, "FAILED", 0)); log.add("Android could not start a foreground print job. No data sent.")
    }
    fun cancel(id: String? = activeId) {
        if (id != null && id == activeId) { log.add("Cancellation requested; closing link. Buffered paper may finish."); activeJob?.cancel(); session.close(); _link.value = LinkState(status = "Disconnected after cancellation") }
    }
    suspend fun execute(request: PrintRequest, output: OutputStream? = null, update: (JobProgress) -> Unit = {}) = serial.withLock {
        val order = if (request.smallTest) listOf(0) else PageSelection.expand(request.selection, request.copies, request.collate)
        activeId = request.id; activeJob = currentCoroutineContext()[Job]
        var sent = 0
        val target = request.printer ?: store.printer.value
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A4Print:job")
        fun emit(message: String, fraction: Float = 0f, result: String = "", running: Boolean = true) {
            val value = JobProgress(request.id, request.title, message, fraction.coerceIn(0f, 1f), running, result, sent, order.size)
            _progress.value = value; update(value)
        }
        try {
            wake.acquire(30 * 60 * 1000L)
            withTimeout(30 * 60 * 1000L) {
                withContext(Dispatchers.IO) {
                    emit(if (output == null) "Connecting to printer…" else "Preparing offline export…")
                    if (output == null) {
                        check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "Grant Nearby devices permission in the app first." }
                        check(target != null) { "Select and pair a printer in the app first." }
                        if (!session.connected || session.address != target.address) session.connect(target, store.insecure.value)
                        _link.value = LinkState(connected = true, status = "Connected")
                        check(session.ready(30_000)) { "Printer did not confirm readiness. No page was sent. Check the printer and connection." }
                        session.setDensity(request.options.density)
                    } else output.write(DyProtocol.density(request.options.density))
                    var previous = -1; var raster: RasterPage? = null
                    for ((position, pageIndex) in order.withIndex()) {
                        ensureActive()
                        emit("Rendering page ${position + 1} of ${order.size}", position.toFloat() / order.size)
                        if (raster == null || previous != pageIndex) {
                            raster = if (request.smallTest) PageRenderer.smallTest() else rasterize(request.set!!.pages[pageIndex], request.options)
                            previous = pageIndex
                        }
                        val packets = DyProtocol.pagePackets(raster!!, request.options.feed)
                        if (output != null) {
                            for (packet in packets) { ensureActive(); output.write(packet) }
                            sent++
                        } else {
                            val confirmed = session.sendPage(packets) { bytes, total ->
                                emit("Sending page ${position + 1} of ${order.size} · ${bytes * 100 / total}%", (position + bytes.toFloat() / total * .85f) / order.size)
                            }
                            sent++
                            if (!confirmed) throw IllegalStateException("Page data sent, but readiness was not confirmed. Check the paper before retrying; remaining pages were not sent.")
                        }
                        emit("${if (output == null) "Sent" else "Exported"} $sent of ${order.size} pages", (position + 1f) / order.size)
                    }
                    output?.flush()
                    val result = if (output == null) "SENT" else "EXPORTED"
                    emit(if (output == null) "Sent to printer · ready response received" else "Printer stream saved · nothing printed", 1f, result, false)
                    store.record(HistoryEntry(request.title, result, sent)); log.add("Job $result: $sent pages. No document content recorded.")
                }
            }
        } catch (e: CancellationException) {
            if (output == null) disconnect()
            emit("Cancelled locally. The printer may finish buffered data.", result = "CANCELLED", running = false)
            store.record(HistoryEntry(request.title, "CANCELLED", sent)); throw e
        } catch (e: Exception) {
            if (output == null) disconnect()
            val message = e.message ?: "Job failed. Check the printer before retrying."
            emit(message, result = "FAILED", running = false)
            store.record(HistoryEntry(request.title, "FAILED", sent)); log.add("Job stopped: ${e.javaClass.simpleName}. No automatic retry."); throw e
        } finally {
            if (wake.isHeld) wake.release()
            activeJob = null; activeId = null
        }
    }
    fun diagnostics(): String = JSONObject()
        .put("app", "A4DY Print").put("version", BuildConfig.VERSION_NAME).put("android_api", Build.VERSION.SDK_INT)
        .put("hardware_validation", "User-reported: pairing, connection, test strip and Android system printing succeeded on firmware v6.0.4; broader device and calibration tests pending")
        .put("hardware_validation_source", "User report, not assistant-run instrumentation")
        .put("device_document_tests", "10 compiled; not yet run")
        .put("protocol", "DY Classic SPP compressed raster").put("connected", link.value.connected)
        .put("status", if (link.value.connected) "Connected" else if (link.value.connecting) "Connecting" else "Disconnected").put("events", JSONArray(log.snapshot())).toString(2)
}
