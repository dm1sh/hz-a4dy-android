// SPDX-License-Identifier: MIT
package app.a4dy.print.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small original vector symbols; no remote assets or vendor artwork. */
object Symbols {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()
    val Print = icon("Print") { moveTo(6f, 8f); lineTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 8f); moveTo(6f, 17f); lineTo(3f, 17f); lineTo(3f, 9f); lineTo(21f, 9f); lineTo(21f, 17f); lineTo(18f, 17f); moveTo(6f, 14f); lineTo(18f, 14f); lineTo(18f, 21f); lineTo(6f, 21f); close(); moveTo(17f, 11.5f); lineTo(18f, 11.5f) }
    val Bluetooth = icon("Bluetooth") { moveTo(7f, 7f); lineTo(17f, 17f); lineTo(12f, 21f); lineTo(12f, 3f); lineTo(17f, 7f); lineTo(7f, 17f) }
    val Document = icon("Document") { moveTo(5f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(5f, 21f); close(); moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f); moveTo(8f, 12f); lineTo(16f, 12f); moveTo(8f, 16f); lineTo(14f, 16f) }
    val Photo = icon("Photo") { moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close(); moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(21f, 17f); moveTo(16f, 8f); lineTo(16.1f, 8f) }
    val Text = icon("Text") { moveTo(4f, 5f); lineTo(20f, 5f); moveTo(12f, 5f); lineTo(12f, 20f); moveTo(8f, 20f); lineTo(16f, 20f); moveTo(4f, 5f); lineTo(4f, 8f); moveTo(20f, 5f); lineTo(20f, 8f) }
    val History = icon("History") { moveTo(4f, 9f); curveTo(7f, -1f, 23f, 3f, 21f, 14f); curveTo(19f, 23f, 6f, 24f, 3f, 16f); moveTo(3f, 4f); lineTo(3f, 10f); lineTo(9f, 10f); moveTo(12f, 7f); lineTo(12f, 13f); lineTo(16f, 15f) }
    val Settings = icon("Settings") { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(20f, 18f); moveTo(8f, 3f); lineTo(8f, 9f); moveTo(16f, 9f); lineTo(16f, 15f); moveTo(10f, 15f); lineTo(10f, 21f) }
    val Arrow = icon("Arrow") { moveTo(4f, 12f); lineTo(20f, 12f); moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f) }
    val Rotate = icon("Rotate") { moveTo(5f, 8f); curveTo(8f, -1f, 23f, 4f, 20f, 15f); curveTo(18f, 22f, 8f, 24f, 4f, 16f); moveTo(4f, 3f); lineTo(4f, 9f); lineTo(10f, 9f) }
    val Download = icon("Download") { moveTo(12f, 3f); lineTo(12f, 16f); moveTo(7f, 11f); lineTo(12f, 16f); lineTo(17f, 11f); moveTo(4f, 17f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 17f) }
    val Close = icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = icon("Check") { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) }
    val Plus = icon("Plus") { moveTo(5f, 12f); lineTo(19f, 12f); moveTo(12f, 5f); lineTo(12f, 19f) }
    val Minus = icon("Minus") { moveTo(5f, 12f); lineTo(19f, 12f) }
}
