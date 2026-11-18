// SPDX-License-Identifier: MIT
package app.a4dy.print

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.print.PageRange
import androidx.test.core.app.ApplicationProvider
import app.a4dy.core.*
import app.a4dy.print.data.*
import app.a4dy.print.service.A4PrintService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35])
class StoreAndServiceTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext<A4Application>()
        context.getSharedPreferences("a4-print", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun optionsRoundTrip() {
        val options = PrintOptions(paper = Paper.LETTER, halftone = Halftone.THRESHOLD, threshold = 190, brightness = -20,
            contrast = 140, density = 2, rotation = 270, mirror = true, negative = true, marginMm = 12,
            cropHorizontal = 5, cropVertical = 17, layout = Layout.FILL, feed = Feed.LABEL)
        AppStore(context).saveOptions(options)
        assertEquals(options, AppStore(context).options.value)
    }
    @Test fun corruptPreferencesRecoverToSafeDefaults() {
        context.getSharedPreferences("a4-print", Context.MODE_PRIVATE).edit().putString("options", "{\"density\":999}")
            .putString("history", "not json").putString("address", "garbage").commit()
        val store = AppStore(context)
        assertEquals(PrintOptions(), store.options.value); assertNull(store.printer.value); assertTrue(store.history.value.isEmpty())
    }
    @Test fun historyIsBoundedAndClearable() {
        val store = AppStore(context)
        repeat(45) { store.record(HistoryEntry("page-$it", "EXPORTED", it)) }
        assertEquals(30, store.history.value.size); assertEquals("page-44", AppStore(context).history.value.first().title)
        store.clearHistory(); assertTrue(AppStore(context).history.value.isEmpty())
    }
    @Test fun printerAndPrivacyPreferencesPersist() {
        val store = AppStore(context); val printer = SavedPrinter("HZ-A4DY", "AA:BB:CC:DD:EE:FF")
        assertFalse(store.insecure.value)
        store.savePrinter(printer); store.setInsecure(true); store.setDynamicColors(true)
        val reread = AppStore(context)
        assertEquals(printer, reread.printer.value); assertTrue(reread.insecure.value); assertTrue(reread.dynamicColors.value)
        reread.savePrinter(null); assertNull(AppStore(context).printer.value)
    }
    @Test fun logIsBoundedAndRedactsAddresses() {
        val log = EventLog(); repeat(201) { log.add("event $it AA:bb:CC:dd:EE:ff") }
        assertEquals(200, log.snapshot().size); assertFalse(log.snapshot().joinToString().contains("AA:bb"))
        assertTrue(log.snapshot().last().contains("[Bluetooth address]"))
    }
    @Test fun spoolerAlreadySelectedPdfIsNotSelectedAgain() {
        assertEquals(listOf(0, 1), A4PrintService.spoolerPages(2, arrayOf(PageRange.ALL_PAGES)))
        assertEquals(listOf(0, 1), A4PrintService.spoolerPages(2, null))
        assertEquals(listOf(0, 1), A4PrintService.spoolerPages(2, emptyArray()))
    }
    @Test fun remainingSpoolerRangesAreAppliedOnce() {
        assertEquals(listOf(1, 2, 4), A4PrintService.spoolerPages(5, arrayOf(PageRange(1, 2), PageRange(4, 4))))
        assertEquals(listOf(0, 1, 2), A4PrintService.spoolerPages(4, arrayOf(PageRange(0, 1), PageRange(1, 2))))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSpoolerRangeFailsInsteadOfPrintingWrongPage() {
        A4PrintService.spoolerPages(2, arrayOf(PageRange(2, 3)))
    }
    @Test fun manifestContainsServiceAndNoInternetOrLocationPermission() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue(requested.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse(requested.contains(Manifest.permission.INTERNET))
        assertFalse(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        val info = context.packageManager.getServiceInfo(android.content.ComponentName(context, A4PrintService::class.java), 0)
        assertEquals(Manifest.permission.BIND_PRINT_SERVICE, info.permission)
        assertTrue(info.exported)
    }
}
