// SPDX-License-Identifier: MIT
package app.a4dy.print

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.a4dy.print.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Real Compose layout/interaction under Robolectric. Not an emulator or a Bluetooth test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private fun capture(name: String) {
        // PixelCopy waits for an actual display fence. Draw the measured real View tree instead.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            val target = File("build/screenshots/$name.png").also { it.parentFile!!.mkdirs() }
            target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun homeAndAllNavigationDestinationsRender() {
        compose.onNodeWithText("Choose a PDF or image").assertIsDisplayed()
        capture("home")
        compose.onNodeWithText("Printers").performClick()
        compose.onNodeWithText("Meet your printer.").assertIsDisplayed()
        compose.onAllNodesWithText("Allow Nearby devices").assertCountEquals(2)
        capture("printers")
        compose.onNodeWithText("Jobs").performClick()
        compose.onNodeWithText("Paper trail.").assertIsDisplayed()
        compose.onNodeWithText("A fresh start.").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Small details.\nBetter pages.").assertIsDisplayed()
        capture("settings")
    }
    @Test fun imagePreviewAndAdjustmentsWorkWithoutBluetooth() {
        val bitmap = android.graphics.Bitmap.createBitmap(600, 850, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 42f }
        canvas.drawText("A4DY Print", 48f, 110f, paint)
        paint.textSize = 20f; canvas.drawText("An original, local print studio.", 48f, 150f, paint)
        paint.strokeWidth = 2f; canvas.drawLine(48f, 185f, 552f, 185f, paint)
        paint.textSize = 18f; canvas.drawText("Test page: 1234567890", 48f, 235f, paint)
        val data = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, data); bitmap.recycle()
        val uri = android.net.Uri.parse("content://a4dy.test/sample.png")
        org.robolectric.Shadows.shadowOf(compose.activity.contentResolver).registerInputStream(uri, data.toByteArray().inputStream())
        compose.runOnIdle { model.import(listOf(uri)) }
        compose.waitUntil(timeoutMillis = 20_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            model.error != null || (model.documentSet != null && model.preview != null && !model.rendering)
        }
        compose.runOnIdle { assertNull(model.error) }
        compose.onNodeWithText("Document").assertIsDisplayed()
        capture("document")
        compose.onNodeWithText("Mirror off").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(model.rt.store.options.value.mirror) }
        compose.onNodeWithText("Rotate 0°").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(90, model.rt.store.options.value.rotation) }
        compose.runOnIdle { model.range = "2" }
        compose.onNodeWithText("Pages to print").performScrollTo()
        compose.onNodeWithText("Page range must be between 1 and 1.").assertExists()
    }
    @Test fun selectedPrinterIsShownWithoutConnecting() {
        compose.runOnIdle { model.select("HZ-A4DY", "AA:BB:CC:DD:EE:FF") }
        compose.onNodeWithText("Printers").performClick()
        compose.runOnIdle {
            assertEquals("AA:BB:CC:DD:EE:FF", model.rt.store.printer.value?.address)
            assertFalse(model.rt.link.value.connected)
        }
        compose.onNodeWithText("YOUR PRINTER").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }
}
