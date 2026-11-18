// SPDX-License-Identifier: MIT
package app.a4dy.print.data

import android.content.Context
import app.a4dy.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** No document contents, addresses or printer serial numbers in exported diagnostics. */
class EventLog {
    private val events = ArrayDeque<String>()
    @Synchronized fun add(message: String) {
        val safe = message.replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"), "[Bluetooth address]")
        events.addLast("${Instant.now()}  ${safe.take(300)}")
        while (events.size > 200) events.removeFirst()
    }
    @Synchronized fun snapshot() = events.toList()
}
data class SavedPrinter(val name: String, val address: String)
data class HistoryEntry(val title: String, val result: String, val pages: Int, val whenMs: Long = System.currentTimeMillis()) {
    val time: String get() = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(whenMs))
}

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("a4-print", Context.MODE_PRIVATE)
    private val _options = MutableStateFlow(readOptions())
    val options = _options.asStateFlow()
    private val _printer = MutableStateFlow(runCatching {
        val address = prefs.getString("address", null) ?: return@runCatching null
        if (!address.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) return@runCatching null
        SavedPrinter(prefs.getString("printerName", "A4DY") ?: "A4DY", address)
    }.getOrNull())
    val printer = _printer.asStateFlow()
    private val _history = MutableStateFlow(readHistory())
    val history = _history.asStateFlow()
    private val _insecure = MutableStateFlow(prefs.getBoolean("insecureFallback", false))
    val insecure = _insecure.asStateFlow()
    private val _dynamicColors = MutableStateFlow(prefs.getBoolean("dynamicColors", false))
    val dynamicColors = _dynamicColors.asStateFlow()

    fun saveOptions(value: PrintOptions) {
        _options.value = value
        val j = JSONObject().put("paper", value.paper.name).put("halftone", value.halftone.name)
            .put("threshold", value.threshold).put("brightness", value.brightness).put("contrast", value.contrast)
            .put("density", value.density).put("rotation", value.rotation).put("mirror", value.mirror)
            .put("negative", value.negative).put("marginMm", value.marginMm).put("cropHorizontal", value.cropHorizontal)
            .put("cropVertical", value.cropVertical).put("layout", value.layout.name).put("feed", value.feed.name)
        prefs.edit().putString("options", j.toString()).apply()
    }
    fun savePrinter(value: SavedPrinter?) {
        _printer.value = value
        prefs.edit().putString("address", value?.address).putString("printerName", value?.name).apply()
    }
    fun setInsecure(value: Boolean) { _insecure.value = value; prefs.edit().putBoolean("insecureFallback", value).apply() }
    fun setDynamicColors(value: Boolean) { _dynamicColors.value = value; prefs.edit().putBoolean("dynamicColors", value).apply() }
    @Synchronized fun record(entry: HistoryEntry) {
        _history.value = (listOf(entry) + _history.value).take(30)
        val a = JSONArray()
        for (e in _history.value) a.put(JSONObject().put("title", e.title.take(150)).put("result", e.result).put("pages", e.pages).put("time", e.whenMs))
        prefs.edit().putString("history", a.toString()).apply()
    }
    fun clearHistory() { _history.value = emptyList(); prefs.edit().remove("history").apply() }
    private fun readOptions(): PrintOptions = runCatching {
        val j = JSONObject(prefs.getString("options", "{}") ?: "{}")
        PrintOptions(
            paper = Paper.valueOf(j.optString("paper", "A4")), halftone = Halftone.valueOf(j.optString("halftone", "DIFFUSION")),
            threshold = j.optInt("threshold", 128), brightness = j.optInt("brightness", 0), contrast = j.optInt("contrast", 100),
            density = j.optInt("density", 0), rotation = j.optInt("rotation", 0), mirror = j.optBoolean("mirror"), negative = j.optBoolean("negative"),
            marginMm = j.optInt("marginMm", 5), cropHorizontal = j.optInt("cropHorizontal", 0), cropVertical = j.optInt("cropVertical", 0),
            layout = Layout.valueOf(j.optString("layout", "FIT")), feed = Feed.valueOf(j.optString("feed", "CONTINUOUS")))
    }.getOrDefault(PrintOptions())
    private fun readHistory(): List<HistoryEntry> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { HistoryEntry(it.getString("title"), it.getString("result"), it.getInt("pages"), it.getLong("time")) }.take(30)
    }.getOrDefault(emptyList())
}
