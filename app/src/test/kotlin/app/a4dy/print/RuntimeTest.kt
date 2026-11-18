// SPDX-License-Identifier: MIT
package app.a4dy.print

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.a4dy.core.*
import app.a4dy.print.bluetooth.PrinterLink
import app.a4dy.print.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RuntimeTest {
    private lateinit var context: Context
    private lateinit var link: FakeLink
    private lateinit var rt: AppRuntime
    private val rasterized = mutableListOf<Int>()
    private val printer = SavedPrinter("HZ-A4DY", "AA:BB:CC:DD:EE:FF")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext<A4Application>()
        context.getSharedPreferences("a4-print", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        link = FakeLink(); rasterized.clear()
        rt = AppRuntime(context, link) { ref, _ ->
            synchronized(rasterized) { rasterized += ref.index }; RasterPage(8, 1, byteArrayOf((0x80 ushr ref.index).toByte()))
        }
        rt.store.savePrinter(printer)
    }
    private fun request(copies: Int = 1, collate: Boolean = true, selection: List<Int> = listOf(0, 1)) = PrintRequest(
        DocumentSet(listOf(DocumentItem(File(context.cacheDir, "fake.pdf"), "Private document name.pdf", true, 2))), PrintOptions(), selection, copies, collate, printer = printer)
    @Test fun offlineExportUsesIdenticalPacketsAndNeverTouchesBluetooth() = runBlocking {
        val out = ByteArrayOutputStream(); rt.execute(request(2, false), out)
        val expected = ByteArrayOutputStream().apply {
            write(DyProtocol.density(0))
            for (index in listOf(0, 0, 1, 1)) DyProtocol.pagePackets(RasterPage(8, 1, byteArrayOf((0x80 ushr index).toByte())), Feed.CONTINUOUS).forEach { write(it) }
        }
        assertArrayEquals(expected.toByteArray(), out.toByteArray())
        assertEquals(listOf(0, 1), rasterized); assertEquals(0, link.connectCalls); assertEquals(0, link.pages.size)
        assertEquals("EXPORTED", rt.progress.value.result); assertFalse(rt.busy)
    }
    @Test fun collatedOrderAndNoExtraHardwareCopies() = runBlocking {
        rt.execute(request(2, true)); assertEquals(listOf(0, 1, 0, 1), rasterized)
        assertEquals(4, link.pages.size); assertEquals(1, link.connectCalls); assertEquals(listOf(0), link.densities)
        assertEquals("SENT", rt.progress.value.result); assertEquals(4, rt.store.history.value.first().pages)
    }
    @Test fun unconfirmedReadyStopsWithoutAutomaticallyResending() = runBlocking {
        link.confirm = false
        assertTrue(runCatching { rt.execute(request()) }.isFailure)
        assertEquals(1, link.pages.size); assertEquals("FAILED", rt.progress.value.result)
        assertEquals(1, rt.progress.value.sentPages); assertFalse(link.connected)
    }
    @Test fun notReadyAtStartSendsNoPage() = runBlocking {
        link.ready = false; assertTrue(runCatching { rt.execute(request()) }.isFailure)
        assertTrue(link.pages.isEmpty()); assertTrue(link.densities.isEmpty()); assertFalse(rt.busy)
    }
    @Test fun pageFailureIsNotRetried() = runBlocking {
        link.fail = true; assertTrue(runCatching { rt.execute(request()) }.isFailure)
        assertEquals(1, link.pages.size); assertEquals("FAILED", rt.progress.value.result); assertFalse(rt.busy)
    }
    @Test fun cancellationClosesLinkAndStopsRemainingPages() = runBlocking {
        link.block = true
        val task = launch(Dispatchers.Default) { rt.execute(request()) }
        withTimeout(5000) { link.entered.await() }
        rt.cancel(); task.join()
        assertTrue(task.isCancelled); assertFalse(link.connected); assertEquals(1, link.pages.size)
        assertEquals("CANCELLED", rt.progress.value.result); assertFalse(rt.busy)
    }
    @Test fun jobsAreSerializedAcrossAppAndPrintService() = runBlocking {
        link.block = true
        val first = launch(Dispatchers.Default) { rt.execute(request(selection = listOf(0))) }
        withTimeout(5000) { link.entered.await() }
        val second = launch(Dispatchers.Default) { rt.execute(request(selection = listOf(1))) }
        delay(30); assertEquals(1, link.pages.size)
        link.release.complete(Unit); first.join(); second.join()
        assertEquals(2, link.pages.size); assertEquals(2, rt.store.history.value.size)
    }
    @Test fun diagnosticsExcludePrivateDocumentNameAndAddress() = runBlocking {
        rt.execute(request(), ByteArrayOutputStream())
        val diagnostics = rt.diagnostics()
        assertFalse(diagnostics.contains("Private document")); assertFalse(diagnostics.contains(printer.address))
        val report = org.json.JSONObject(diagnostics)
        assertTrue(report.getString("hardware_validation").contains("User-reported"))
        assertTrue(report.getString("hardware_validation").contains("v6.0.4"))
        assertEquals("User report, not assistant-run instrumentation", report.getString("hardware_validation_source"))
        assertEquals("10 compiled; not yet run", report.getString("device_document_tests"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSelectionRejectedBeforeAnyJobStarts() { request(selection = listOf(2)) }

    private class FakeLink : PrinterLink {
        override var connected = false
        override var address: String? = null
        var connectCalls = 0; var ready = true; var confirm = true; var fail = false; var block = false
        val pages = java.util.Collections.synchronizedList(mutableListOf<List<ByteArray>>())
        val densities = mutableListOf<Int>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        override suspend fun connect(printer: SavedPrinter, allowInsecure: Boolean) { connectCalls++; connected = true; address = printer.address }
        override fun close() { connected = false }
        override suspend fun exchange(command: ByteArray, timeoutMs: Long) = byteArrayOf()
        override suspend fun status() = PrinterStatus.READY
        override suspend fun ready(maxWaitMs: Long) = ready
        override suspend fun setDensity(level: Int) { densities += level }
        override suspend fun sendPage(packets: List<ByteArray>, progress: (Int, Int) -> Unit): Boolean {
            pages += packets; entered.complete(Unit)
            if (block) release.await()
            if (fail) throw IOException("Simulated partial transmission. Never retry automatically.")
            val total = packets.sumOf { it.size }; progress(total, total); return confirm
        }
    }
}
