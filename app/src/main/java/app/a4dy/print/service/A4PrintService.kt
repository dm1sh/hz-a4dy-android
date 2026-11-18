// SPDX-License-Identifier: MIT
package app.a4dy.print.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import app.a4dy.core.*
import app.a4dy.print.PrintRequest
import app.a4dy.print.data.DocumentSet
import app.a4dy.print.runtime
import kotlinx.coroutines.*

/** Android's spooler normally shreds selected pages and sets pages=ALL_PAGES before delivery.
 * Do not apply the original user range a second time. Respect remaining ranges if supplied.
 * All PrintJob / PrintDocument API calls stay on the main thread, PDF I/O and rendering do not.
 */
class A4PrintService : PrintService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = mutableMapOf<String, Job>()
    private val runtimeIds = mutableMapOf<String, String>()
    private val descriptors = mutableMapOf<String, ParcelFileDescriptor>()
    override fun onConnected() {
        super.onConnected()
        for (job in activePrintJobs) {
            if (tasks.containsKey(job.id.toString())) continue
            if (job.isStarted || job.isBlocked) runCatching { job.fail("Service interrupted. Paper may already have printed; check it before retrying manually.") }
            else if (job.isQueued) onPrintJobQueued(job)
        }
    }
    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        fun refresh() {
            val configured = runtime.store.printer.value
            val old = printers.map { it.id }
            val result = mutableListOf<PrinterInfo>()
            if (configured != null) {
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                val permission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                val available = permission && adapter?.isEnabled == true
                val id = generatePrinterId(configured.address)
                val cap = PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true).addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addResolution(PrintAttributes.Resolution("203", "203 dpi", 203, 203), true)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE).build()
                result += PrinterInfo.Builder(id, "${configured.name} · A4DY", if (available) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE)
                    .setDescription("Experimental · Bluetooth Classic · 203 dpi monochrome").setCapabilities(cap).build()
            }
            removePrinters(old.filter { oldId -> result.none { it.id == oldId } }); addPrinters(result)
        }
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = refresh()
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = refresh()
        override fun onStartPrinterStateTracking(printerId: PrinterId) = refresh()
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }
    override fun onPrintJobQueued(job: PrintJob) {
        val info = job.info
        val id = info.id.toString()
        if (tasks.containsKey(id)) return
        val saved = runtime.store.printer.value
        if (saved == null || saved.address != info.printerId?.localId) { job.fail("Configure this printer in A4DY Print first."); return }
        val fd = job.document.data
        if (fd == null) { job.fail("Android did not supply a PDF document."); return }
        if (!job.start()) { fd.close(); return }
        descriptors[id] = fd
        val requestedRanges = info.pages
        val copies = info.copies
        val attrs = info.attributes
        job.setStatus("Waiting for Bluetooth printer…")
        tasks[id] = scope.launch {
            var temporary: java.io.File? = null
            try {
                val document = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { runtime.documents.copy(it, info.label, spool = true) }
                }
                temporary = document.file
                val pages = spoolerPages(document.pages, requestedRanges)
                val media = attrs?.mediaSize
                val paper = when (media?.id) {
                    PrintAttributes.MediaSize.ISO_A5.id -> Paper.A5
                    PrintAttributes.MediaSize.NA_LETTER.id -> Paper.LETTER
                    else -> Paper.A4
                }
                // The spooler PDF already includes requested margins, scaling and page selection.
                val options = runtime.store.options.value.copy(paper = paper, marginMm = 0, rotation = if (media?.isPortrait == false) 90 else 0,
                    cropHorizontal = 0, cropVertical = 0, mirror = false, negative = false, layout = Layout.FIT)
                val request = PrintRequest(DocumentSet(listOf(document)), options, pages, copies, collate = true, printer = saved, title = info.label)
                runtimeIds[id] = request.id
                var lastUpdate = 0L
                runtime.execute(request) { state ->
                    val now = SystemClock.elapsedRealtime()
                    if (!state.running || now - lastUpdate >= 250) {
                        lastUpdate = now
                        scope.launch { runCatching { if (job.isStarted || job.isBlocked) { job.setProgress(state.fraction); job.setStatus(state.message) } } }
                    }
                }
                runCatching { job.complete() }
            } catch (_: CancellationException) { runCatching { job.cancel() } }
            catch (e: Exception) { runCatching { job.fail(e.message ?: "Printing failed. Check the paper before retrying.") } }
            finally { runCatching { fd.close() }; temporary?.delete(); tasks.remove(id); runtimeIds.remove(id); descriptors.remove(id) }
        }
    }
    override fun onRequestCancelPrintJob(job: PrintJob) {
        val id = job.id.toString()
        runtimeIds[id]?.let { runtime.cancel(it) }
        tasks[id]?.cancel()
        runCatching { descriptors.remove(id)?.close() }
        job.cancel()
    }
    override fun onDestroy() {
        runtimeIds.values.forEach { runtime.cancel(it) }; descriptors.values.forEach { runCatching { it.close() } }
        descriptors.clear(); scope.cancel(); super.onDestroy()
    }
    companion object {
        fun spoolerPages(count: Int, ranges: Array<PageRange>?): List<Int> {
            require(count in 1..500)
            if (ranges.isNullOrEmpty() || ranges.any { it == PageRange.ALL_PAGES }) return (0 until count).toList()
            val pages = linkedSetOf<Int>()
            for (range in ranges) {
                require(range.start in 0 until count && range.end in range.start until count) { "Invalid page range supplied by Android spooler." }
                pages.addAll(range.start..range.end)
            }
            return pages.toList()
        }
    }
}
