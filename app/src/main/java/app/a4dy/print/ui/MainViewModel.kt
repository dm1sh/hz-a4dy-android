// SPDX-License-Identifier: MIT
package app.a4dy.print.ui

import android.Manifest
import android.app.Application
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.a4dy.core.*
import app.a4dy.print.PrintRequest
import app.a4dy.print.data.*
import app.a4dy.print.render.PageRenderer
import app.a4dy.print.runtime
import app.a4dy.print.service.ForegroundPrintService
import kotlinx.coroutines.*

data class NearbyPrinter(val name: String, val address: String, val paired: Boolean)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val rt = app.runtime
    var documentSet by mutableStateOf<DocumentSet?>(null); private set
    var previewPage by mutableIntStateOf(0); private set
    var preview by mutableStateOf<android.graphics.Bitmap?>(null); private set
    var rendering by mutableStateOf(false); private set
    var importing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var tab by mutableIntStateOf(0)
    var range by mutableStateOf("")
    var copies by mutableIntStateOf(1)
    var collate by mutableStateOf(true)
    var devices by mutableStateOf<List<NearbyPrinter>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var deviceRevision by mutableIntStateOf(0); private set
    var pendingSubmit by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    private var previewJob: Job? = null
    private var exportRequest: PrintRequest? = null
    private val adapter get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val hasBluetooth get() = adapter != null
    val permissionGranted get() = ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    val bluetoothEnabled get() = permissionGranted && adapter?.isEnabled == true
    private val found = linkedMapOf<String, NearbyPrinter>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!permissionGranted) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) { rememberDevice(device); publishDevices() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                BluetoothAdapter.ACTION_STATE_CHANGED -> { if (!bluetoothEnabled) { scanning = false; rt.disconnect() }; refreshDevices() }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshDevices()
            }
        }
    }
    init {
        ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        refreshDevices()
        viewModelScope.launch { rt.store.options.collect { refreshPreview() } }
        viewModelScope.launch { rt.progress.collect { if (it.id.isNotBlank()) pendingSubmit = false } }
    }
    fun refreshDevices() {
        deviceRevision++
        if (!permissionGranted) { devices = emptyList(); return }
        try { adapter?.bondedDevices?.forEach(::rememberDevice); publishDevices() }
        catch (_: SecurityException) { error = "Bluetooth permission changed. Grant Nearby devices again." }
    }
    private fun rememberDevice(device: BluetoothDevice) {
        try {
            val item = NearbyPrinter(device.name.orEmpty().ifBlank { "Unnamed Bluetooth device" }, device.address, device.bondState == BluetoothDevice.BOND_BONDED)
            found[item.address] = item
        } catch (_: SecurityException) { /* Permissions can be revoked while a scan is in flight. */ }
    }
    private fun publishDevices() {
        devices = found.values.sortedWith(compareByDescending<NearbyPrinter> { isTarget(it.name) }.thenByDescending { it.paired }.thenBy { it.name })
    }
    fun scan() {
        if (!permissionGranted) return
        if (!bluetoothEnabled) { error = "Turn on Bluetooth first."; return }
        try {
            if (scanning) { adapter?.cancelDiscovery(); scanning = false }
            else { refreshDevices(); scanning = adapter?.startDiscovery() == true; if (!scanning) error = "Bluetooth scan could not start. Try Android Bluetooth settings." }
        } catch (_: SecurityException) { scanning = false; error = "Grant Nearby devices permission again." }
    }
    fun select(name: String, address: String) {
        if (rt.busy || pendingSubmit || connecting) return
        val normalized = address.trim().uppercase()
        require(BluetoothAdapter.checkBluetoothAddress(normalized)) { "Enter a valid address, for example AA:BB:CC:DD:EE:FF." }
        rt.disconnect(); rt.store.savePrinter(SavedPrinter(name.ifBlank { "A4DY" }.take(80), normalized))
    }
    fun connect() {
        if (connecting || rt.busy) return
        connecting = true
        viewModelScope.launch {
            try { rt.connect() } catch (e: Exception) { error = e.message ?: "Connection failed." }
            finally { connecting = false }
        }
    }
    fun import(uris: List<Uri>) {
        if (rt.busy || pendingSubmit || importing) { error = "Finish or cancel the current job first."; return }
        importing = true; error = null
        viewModelScope.launch {
            val imported = mutableListOf<DocumentItem>()
            try {
                require(uris.size in 1..30) { "Choose 1–30 files at a time." }
                withContext(Dispatchers.IO) { for (uri in uris) imported += rt.documents.import(uri) }
                val set = DocumentSet(imported)
                require(set.pages.size <= 500) { "Choose no more than 500 pages in total." }
                documentSet = set; previewPage = 0; range = ""; tab = 0; refreshPreview()
            } catch (e: Exception) { imported.forEach { it.file.delete() }; error = e.message ?: "Could not read the document." }
            finally { importing = false }
        }
    }
    fun text(text: String) {
        if (rt.busy || pendingSubmit) return
        importing = true
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) { rt.documents.text(text) }
                documentSet = DocumentSet(listOf(document)); previewPage = 0; range = ""; tab = 0; refreshPreview()
            } catch (e: Exception) { error = e.message ?: "Could not create the note." }
            finally { importing = false }
        }
    }
    fun options(value: PrintOptions) { rt.store.saveOptions(value) }
    fun movePage(delta: Int) {
        val count = documentSet?.pages?.size ?: return
        previewPage = (previewPage + delta).coerceIn(0, count - 1); refreshPreview()
    }
    fun refreshPreview() {
        previewJob?.cancel()
        val ref = documentSet?.pages?.getOrNull(previewPage) ?: run { preview = null; return }
        val options = rt.store.options.value
        previewJob = viewModelScope.launch {
            rendering = true
            try {
                delay(180)
                val bitmap = withContext(Dispatchers.Default) { PageRenderer.preview(PageRenderer.render(ref, options)) }
                preview = bitmap
            } catch (_: CancellationException) { throw CancellationException() }
            catch (e: Exception) { error = e.message ?: "Preview could not be rendered." }
            finally { rendering = false }
        }
    }
    private fun request(small: Boolean = false): PrintRequest {
        val set = documentSet
        if (!small) check(set != null) { "Choose a document first." }
        return PrintRequest(if (small) null else set, rt.store.options.value.let { if (small) it.copy(feed = Feed.CONTINUOUS, density = 0) else it },
            if (small) listOf(0) else PageSelection.parse(range, set!!.pages.size), if (small) 1 else copies,
            collate, smallTest = small, printer = rt.store.printer.value)
    }
    fun print(small: Boolean = false) {
        if (rt.busy || pendingSubmit || importing || connecting) return
        try {
            check(permissionGranted) { "Grant Nearby devices permission on the Printers tab first." }
            check(bluetoothEnabled) { "Turn on Bluetooth first." }
            check(rt.store.printer.value != null) { "Select a printer on the Printers tab first." }
            val request = request(small); PageSelection.expand(request.selection, request.copies, request.collate)
            pendingSubmit = true; ForegroundPrintService.start(getApplication(), request)
        } catch (e: Exception) { pendingSubmit = false; error = e.message ?: "Cannot start printing." }
    }
    fun cancel() {
        rt.cancel(); pendingSubmit = false
        runCatching { ForegroundPrintService.cancel(app) }
    }
    fun prepareExport(): Boolean = try { exportRequest = request(); true } catch (e: Exception) { error = e.message; false }
    fun export(uri: Uri?) {
        val request = exportRequest; exportRequest = null
        if (uri == null || request == null) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Could not create the output file.")
                    out.use { rt.execute(request, it) }
                }
            } catch (_: CancellationException) { runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) } }
            catch (e: Exception) { error = e.message; runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) } }
        }
    }
    fun saveDiagnostics(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(rt.diagnostics()) } ?: error("Cannot create diagnostics file.") }
                .onFailure { withContext(Dispatchers.Main) { error = it.message } }
        }
    }
    fun clearImports() {
        if (rt.busy || pendingSubmit || importing) return
        previewJob?.cancel(); documentSet = null; preview = null
        rt.documents.clearCache()
    }
    override fun onCleared() { if (permissionGranted) try { adapter?.cancelDiscovery() } catch (_: SecurityException) { }; getApplication<Application>().unregisterReceiver(receiver); super.onCleared() }
    companion object { fun isTarget(name: String?) = name?.contains(Regex("(?i)A4DY|A4RJ|GD-88|XL-A401D")) == true }
}
