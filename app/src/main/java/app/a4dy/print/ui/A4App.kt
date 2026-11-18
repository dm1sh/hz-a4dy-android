// SPDX-License-Identifier: MIT
package app.a4dy.print.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.a4dy.core.*
import app.a4dy.print.BuildConfig
import app.a4dy.print.JobProgress
import app.a4dy.print.LinkState
import app.a4dy.print.data.HistoryEntry
import app.a4dy.print.data.SavedPrinter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun A4App(model: MainViewModel) {
    val context = LocalContext.current
    val options by model.rt.store.options.collectAsStateWithLifecycle()
    val printer by model.rt.store.printer.collectAsStateWithLifecycle()
    val link by model.rt.link.collectAsStateWithLifecycle()
    val progress by model.rt.progress.collectAsStateWithLifecycle()
    val history by model.rt.store.history.collectAsStateWithLifecycle()
    val insecure by model.rt.store.insecure.collectAsStateWithLifecycle()
    val dynamic by model.rt.store.dynamicColors.collectAsStateWithLifecycle()
    var noteOpen by rememberSaveable { mutableStateOf(false) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var testOpen by rememberSaveable { mutableStateOf(false) }
    var clearOpen by rememberSaveable { mutableStateOf(false) }
    var unknownDevice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    // Permission state can change outside our process, including while Settings is in front.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { permissionRevision++; model.refreshDevices() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++; model.refreshDevices() }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.print() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++; model.refreshDevices() }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.import(it) }
    val stream = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { model.export(it) }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.saveDiagnostics(it) }
    val granted = remember(permissionRevision) { model.permissionGranted }
    val enabled = remember(permissionRevision, model.deviceRevision, model.scanning, link) { model.bluetoothEnabled }
    val locked = progress.running || model.pendingSubmit || model.connecting || model.importing
    fun openSettings(action: String) { runCatching { context.startActivity(Intent(action)) }.onFailure { model.error = "Android could not open these settings." } }
    fun requestPrint() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else model.print()
    }
    Scaffold(
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Symbols.Print, null, Modifier.size(23.dp), MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(11.dp))
                    Text("A4DY", fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    Text("PRINT STUDIO", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp)); BadgeLabel("TEST BUILD")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                listOf(Triple("Print", Symbols.Document, 0), Triple("Printers", Symbols.Bluetooth, 1), Triple("Jobs", Symbols.History, 2), Triple("Settings", Symbols.Settings, 3)).forEach { (label, icon, tab) ->
                    NavigationBarItem(selected = model.tab == tab, onClick = { model.tab = tab }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                if (model.error != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(model.error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { model.error = null }) { Icon(Symbols.Close, "Dismiss error") }
                        }
                    }
                }
                if (progress.running || model.pendingSubmit || progress.result.isNotBlank()) {
                    JobPanel(progress, model.pendingSubmit, onCancel = model::cancel, onDismiss = { model.rt.dismissProgress() })
                }
                if (model.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (model.tab) {
                    0 -> if (model.documentSet == null) WelcomeScreen(printer, link, locked,
                        onImport = { documents.launch(arrayOf("application/pdf", "image/*")) }, onPhotos = { documents.launch(arrayOf("image/*")) },
                        onText = { noteOpen = true }, onPrinter = { model.tab = 1 }, onTest = { testOpen = true })
                    else DocumentScreen(model, options, locked, printer,
                        onImport = { documents.launch(arrayOf("application/pdf", "image/*")) }, onPrint = { requestPrint() },
                        onExport = { if (model.prepareExport()) stream.launch("a4dy-print-stream.bin") })
                    1 -> PrinterScreen(model, printer, link, locked, granted, enabled,
                        onPermission = { permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) },
                        onEnable = { runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { model.error = "Enable Bluetooth in Android settings." } },
                        onPair = { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }, onManual = { manualOpen = true }, onTest = { testOpen = true },
                        onPrintSettings = { openSettings(Settings.ACTION_PRINT_SETTINGS) }, onSelect = { name, address ->
                            if (MainViewModel.isTarget(name)) runCatching { model.select(name, address) }.onFailure { model.error = it.message }
                            else unknownDevice = name to address
                        })
                    2 -> HistoryScreen(history, onClear = model.rt.store::clearHistory)
                    3 -> SettingsScreen(options, locked, insecure, dynamic, model,
                        onClear = { clearOpen = true }, onDiagnostics = { diagnostics.launch("a4dy-diagnostics.json") }, onPrintSettings = { openSettings(Settings.ACTION_PRINT_SETTINGS) })
                }
            }
        }
    }
    if (noteOpen) NoteDialog(onDismiss = { noteOpen = false }, onCreate = { noteOpen = false; model.text(it) })
    if (manualOpen) ManualPrinterDialog(onDismiss = { manualOpen = false }, onSave = { name, address ->
        runCatching { model.select(name, address) }.onSuccess { manualOpen = false }.onFailure { model.error = it.message }
    })
    if (testOpen) AlertDialog(onDismissRequest = { testOpen = false }, icon = { Icon(Symbols.Print, null) }, title = { Text("Try a small test first") },
        text = { Text("Basic Bluetooth and Android system printing were user-reported working on firmware v6.0.4. Other devices and advanced behavior still need testing. Load thermal paper and close other printer apps.\n\nThe test sends a 640 × 200 dot pattern at density 0, followed by a short continuous feed. It will use paper. Cancel closes the connection, but buffered data may still print.") },
        confirmButton = { TextButton(enabled = !locked, onClick = { testOpen = false; model.print(small = true) }) { Text("Print test strip") } },
        dismissButton = { TextButton(onClick = { testOpen = false }) { Text("Not now") } })
    if (clearOpen) AlertDialog(onDismissRequest = { clearOpen = false }, title = { Text("Remove imported files?") },
        text = { Text("This clears the app’s private document cache and closes your current document. Original files in Android storage are not changed.") },
        confirmButton = { TextButton(onClick = { model.clearImports(); clearOpen = false }) { Text("Clear imported files") } },
        dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Keep files") } })
    unknownDevice?.let { (name, address) ->
        AlertDialog(onDismissRequest = { unknownDevice = null }, title = { Text("Use this Bluetooth device?") },
            text = { Text("$name is not recognised as an A4DY-family name. This app sends the DY Classic SPP protocol, not a generic Bluetooth printer language. Only continue if you know this is your compatible printer.") },
            confirmButton = { TextButton(onClick = { runCatching { model.select(name, address) }.onFailure { model.error = it.message }; unknownDevice = null }) { Text("Select anyway") } },
            dismissButton = { TextButton(onClick = { unknownDevice = null }) { Text("Cancel") } })
    }
}

@Composable private fun WelcomeScreen(printer: SavedPrinter?, link: LinkState, locked: Boolean, onImport: () -> Unit, onPhotos: () -> Unit, onText: () -> Unit, onPrinter: () -> Unit, onTest: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column {
            Eyebrow("YOUR PHONE. YOUR PAPER.")
            Spacer(Modifier.height(12.dp))
            Text("Make room\nfor the real thing.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("Documents, photos and little ideas.\nPrinted locally, over Bluetooth.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.fillMaxWidth().height(205.dp)) {
                PaperIllustration(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    BadgeLabel("OFFLINE BY DESIGN", MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .09f))
                    Spacer(Modifier.height(8.dp))
                    Text("A4. Without the cables.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Button(onClick = onImport, enabled = !locked, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)) {
            Icon(Symbols.Plus, null, Modifier.size(21.dp)); Spacer(Modifier.width(10.dp)); Text("Choose a PDF or image", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Photos", "From your files", Symbols.Photo, Modifier.weight(1f), !locked, onPhotos)
            ActionTile("Quick note", "Type it. Print it.", Symbols.Text, Modifier.weight(1f), !locked, onText)
        }
        PrinterSummary(printer, link, onPrinter)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("Before the first page")
            StepRow("01", "Pair your printer", "Choose your HZ-A4DY on the Printers tab.")
            StepRow("02", "Start small", "Send a test strip before a full document.")
            StepRow("03", "Review, then print", "Preview the exact black-and-white raster.")
            TextButton(onClick = onTest, enabled = !locked && printer != null, contentPadding = PaddingValues(0.dp)) { Text("Print a small test strip"); Spacer(Modifier.width(8.dp)); Icon(Symbols.Arrow, null, Modifier.size(18.dp)) }
        }
        Text("An independent app for HZ-A4DY · Bluetooth support is experimental", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun PaperIllustration(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val x = size.width * .69f; val y = size.height * .36f
        drawCircle(ink.copy(alpha = .05f), size.height * .63f, androidx.compose.ui.geometry.Offset(size.width * .87f, size.height * .4f))
        // A small original line drawing: a sheet emerging from a portable printer.
        val w = size.height * .6f; val h = size.height * .28f
        val px = x - w / 2; val py = y + 7.dp.toPx()
        drawRoundRect(ink, androidx.compose.ui.geometry.Offset(px - 9.dp.toPx(), py), androidx.compose.ui.geometry.Size(w + 18.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
        drawRoundRect(Color(0xFFFBFCF8), androidx.compose.ui.geometry.Offset(px + 11.dp.toPx(), y - h * .96f), androidx.compose.ui.geometry.Size(w - 22.dp.toPx(), h * 2.2f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        val sx = px + 22.dp.toPx(); val sy = y - h * .67f
        drawLine(ink, androidx.compose.ui.geometry.Offset(sx, sy), androidx.compose.ui.geometry.Offset(sx + w * .33f, sy), 2.dp.toPx())
        for (i in 1..3) drawLine(ink.copy(alpha = .25f), androidx.compose.ui.geometry.Offset(sx, sy + 10.dp.toPx() * i), androidx.compose.ui.geometry.Offset(sx + w * .47f, sy + 10.dp.toPx() * i), 1.4.dp.toPx())
        drawCircle(ink.copy(alpha = .5f), 2.dp.toPx(), androidx.compose.ui.geometry.Offset(px + w - 3.dp.toPx(), py + h * .7f))
        val a = androidx.compose.ui.geometry.Offset(px - 22.dp.toPx(), y - h * .55f)
        drawLine(ink.copy(alpha = .45f), a, a.copy(x = a.x - 11.dp.toPx(), y = a.y - 3.dp.toPx()), 1.5.dp.toPx())
        drawLine(ink.copy(alpha = .45f), a.copy(x = a.x + 5.dp.toPx(), y = a.y - 9.dp.toPx()), a.copy(x = a.x, y = a.y - 20.dp.toPx()), 1.5.dp.toPx())
    }
}

@Composable private fun DocumentScreen(model: MainViewModel, options: PrintOptions, locked: Boolean, printer: SavedPrinter?, onImport: () -> Unit, onPrint: () -> Unit, onExport: () -> Unit) {
    val set = model.documentSet ?: return
    val rangeResult = remember(model.range, set) { runCatching { PageSelection.parse(model.range, set.pages.size) } }
    val selected = rangeResult.getOrNull()?.size ?: 0
    val total = selected * model.copies
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("READY WHEN YOU ARE")
                Spacer(Modifier.height(7.dp)); Text("Your next page.", style = MaterialTheme.typography.headlineMedium)
            }
            IconButton(onClick = onImport, enabled = !locked) { Icon(Symbols.Plus, "Open another document") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Symbols.Document, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(set.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${set.pages.size} page${if (set.pages.size == 1) "" else "s"} · ${options.paper.label} · 203 dpi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 345.dp).fillMaxWidth().aspectRatio(options.paper.widthDots.toFloat() / options.paper.heightDots).background(Color.White, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                    model.preview?.let { Image(it.asImageBitmap(), "Monochrome preview of page ${model.previewPage + 1}", Modifier.fillMaxSize()) }
                    if (model.rendering) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { model.movePage(-1) }, enabled = model.previewPage > 0) { Text("‹ Previous") }
                    Text("${model.previewPage + 1} / ${set.pages.size}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { model.movePage(1) }, enabled = model.previewPage < set.pages.lastIndex) { Text("Next ›") }
                }
                Text("Raster preview · paper output may differ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { model.options(options.copy(rotation = (options.rotation + 90) % 360)) }, enabled = !locked, modifier = Modifier.weight(1f)) { Icon(Symbols.Rotate, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Rotate ${options.rotation}°") }
            OutlinedButton(onClick = { model.options(options.copy(mirror = !options.mirror)) }, enabled = !locked, modifier = Modifier.weight(1f), colors = if (options.mirror) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ButtonDefaults.outlinedButtonColors()) { Text(if (options.mirror) "Mirror on" else "Mirror off") }
        }
        SectionHeading("Pages & copies")
        OutlinedTextField(value = model.range, onValueChange = { model.range = it.take(2000) }, enabled = !locked, modifier = Modifier.fillMaxWidth(), label = { Text("Pages to print") }, placeholder = { Text("All pages") },
            singleLine = true, isError = rangeResult.isFailure, supportingText = { Text(if (rangeResult.isFailure) rangeResult.exceptionOrNull()?.message.orEmpty() else "Leave blank for all, or use 1-3,5") }, shape = RoundedCornerShape(14.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Copies", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { model.copies-- }, enabled = !locked && model.copies > 1) { Icon(Symbols.Minus, "Fewer copies") }
                    Text(model.copies.toString(), Modifier.widthIn(min = 28.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { model.copies++ }, enabled = !locked && model.copies < 99) { Icon(Symbols.Plus, "More copies") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Collate copies", if (model.collate) "1, 2 · 1, 2" else "1, 1 · 2, 2", model.collate, !locked) { model.collate = it }
            }
        }
        ExpandableSection("Page setup", "${options.paper.label} · ${if (options.layout == app.a4dy.core.Layout.FIT) "Fit" else "Fill"} · ${options.marginMm} mm margin", true) {
            PrintLayoutOptions(options, !locked, model::options)
        }
        ExpandableSection("Image adjustments", if (options.halftone == Halftone.DIFFUSION) "Dithered · good for photos" else "Threshold · good for text", false) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = options.halftone == Halftone.DIFFUSION, onClick = { model.options(options.copy(halftone = Halftone.DIFFUSION)) }, enabled = !locked, label = { Text("Photo / dither") })
                FilterChip(selected = options.halftone == Halftone.THRESHOLD, onClick = { model.options(options.copy(halftone = Halftone.THRESHOLD)) }, enabled = !locked, label = { Text("Text / threshold") })
            }
            IntSlider("Brightness", options.brightness, -100..100, !locked) { model.options(options.copy(brightness = it)) }
            IntSlider("Contrast", options.contrast, 20..200, !locked, "%") { model.options(options.copy(contrast = it)) }
            IntSlider("Black threshold", options.threshold, 0..255, !locked) { model.options(options.copy(threshold = it)) }
            IntSlider("Crop left & right", options.cropHorizontal, 0..40, !locked, "% each") { model.options(options.copy(cropHorizontal = it)) }
            IntSlider("Crop top & bottom", options.cropVertical, 0..40, !locked, "% each") { model.options(options.copy(cropVertical = it)) }
            ToggleRow("Negative", "Invert black and white. Uses more heat and black coverage.", options.negative, !locked) { model.options(options.copy(negative = it)) }
            TextButton(enabled = !locked, onClick = { model.options(options.copy(halftone = Halftone.DIFFUSION, brightness = 0, contrast = 100, threshold = 128, cropHorizontal = 0, cropVertical = 0, negative = false, mirror = false, rotation = 0)) }) { Text("Reset adjustments") }
        }
        PrinterSummary(printer, model.rt.link.collectAsStateWithLifecycle().value) { model.tab = 1 }
        Button(onClick = onPrint, enabled = !locked && selected > 0 && total <= 2000 && printer != null, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)) {
            Icon(Symbols.Print, null, Modifier.size(21.dp)); Spacer(Modifier.width(10.dp)); Text("Print $total page${if (total == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onExport, enabled = !locked && selected > 0 && total <= 2000, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)) { Icon(Symbols.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)); Text("Export printer stream · offline") }
        if (total > 2000) Text("A job is limited to 2,000 printed pages.", color = MaterialTheme.colorScheme.error)
        Text("Review your paper after any interrupted job. The app never automatically retries a partially sent page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun PrintLayoutOptions(options: PrintOptions, enabled: Boolean, onChange: (PrintOptions) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Paper.entries.forEach { paper -> FilterChip(options.paper == paper, onClick = { onChange(options.copy(paper = paper)) }, enabled = enabled, label = { Text(paper.label) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(options.layout == app.a4dy.core.Layout.FIT, onClick = { onChange(options.copy(layout = app.a4dy.core.Layout.FIT)) }, enabled = enabled, label = { Text("Fit whole page") })
        FilterChip(options.layout == app.a4dy.core.Layout.FILL, onClick = { onChange(options.copy(layout = app.a4dy.core.Layout.FILL)) }, enabled = enabled, label = { Text("Fill & crop") })
    }
    IntSlider("Paper margin", options.marginMm, 0..20, enabled, "mm") { onChange(options.copy(marginMm = it)) }
}

@Composable private fun PrinterScreen(model: MainViewModel, printer: SavedPrinter?, link: LinkState, locked: Boolean, granted: Boolean, enabled: Boolean, onPermission: () -> Unit, onEnable: () -> Unit, onPair: () -> Unit, onManual: () -> Unit, onTest: () -> Unit, onPrintSettings: () -> Unit, onSelect: (String, String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column { Eyebrow("THE LAST LITTLE CONNECTION"); Spacer(Modifier.height(9.dp)); Text("Meet your printer.", style = MaterialTheme.typography.headlineMedium) }
        Text("Bluetooth Classic, not Wi-Fi or BLE. Pair your HZ-A4DY and keep it close by.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !model.hasBluetooth -> Notice("No Bluetooth adapter", "You can still prepare documents and export printer streams on this device.")
            !granted -> {
                Notice("Allow Nearby devices", "Needed only to find and connect to your printer. No location permission, internet or account is used.")
                Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text("Allow Nearby devices") }
                TextButton(onClick = { val context = model.getApplication<android.app.Application>(); context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open app permission settings") }
            }
            !enabled -> Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Icon(Symbols.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text("Turn on Bluetooth") }
        }
        if (printer != null) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Symbols.Print, null, Modifier.size(31.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Eyebrow("YOUR PRINTER")
                            Text(printer.name, style = MaterialTheme.typography.titleLarge)
                            Text(printer.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (link.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(8.dp)); Text(if (model.connecting) "Connecting…" else link.status, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (link.firmware.isNotBlank()) Text("Firmware: ${link.firmware}", style = MaterialTheme.typography.bodySmall)
                    link.batteryCode?.let { Text("Battery response: $it · vendor units unknown", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = model::connect, enabled = granted && enabled && !locked, modifier = Modifier.weight(1f)) { Text(if (link.connected) "Refresh status" else "Connect") }
                        OutlinedButton(onClick = { model.rt.disconnect() }, enabled = link.connected && !locked) { Text("Disconnect") }
                    }
                    OutlinedButton(onClick = onTest, enabled = granted && enabled && !locked, modifier = Modifier.fillMaxWidth()) { Text("Print a small test strip") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("Paired & nearby", Modifier.weight(1f))
            TextButton(onClick = model::scan, enabled = granted && enabled && !locked) { Text(if (model.scanning) "Stop scan" else "Scan") }
        }
        if (model.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (model.devices.isEmpty()) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text("No devices listed yet. Turn on the printer, pair in Android settings, then return here or tap Scan.", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        model.devices.forEach { device ->
            val name = device.name
            val paired = device.paired
            val selected = printer?.address == device.address
            Surface(onClick = { onSelect(name, device.address) }, enabled = !locked, shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Icon(Symbols.Bluetooth, null)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text("${if (paired) "Paired" else "Nearby"} · ${device.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) Icon(Symbols.Check, "Selected printer")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPair, enabled = !locked, modifier = Modifier.weight(1f)) { Text("Pair in Settings") }
            OutlinedButton(onClick = onManual, enabled = !locked, modifier = Modifier.weight(1f)) { Text("Add address") }
        }
        HorizontalDivider()
        SectionHeading("Print from other apps")
        Text("1. Select a printer here and grant Nearby devices.\n2. Enable “A4DY Bluetooth printing” in Android.\n3. In another app, choose Print → your A4DY printer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 24.sp)
        OutlinedButton(onClick = onPrintSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Android printing settings") }
        Notice("Initial user test succeeded", "Pairing, connection, a test strip and Android system printing were reported working on firmware v6.0.4. Density/feed calibration, ranges/copies and failure handling still need broader tests.")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun HistoryScreen(history: List<HistoryEntry>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp)); Eyebrow("RECENT ACTIVITY"); Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Paper trail.", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if (history.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))
        Text("“Sent” confirms data transmission and a ready response, not that a sheet physically finished.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        if (history.isEmpty()) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Symbols.History, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("A fresh start.", style = MaterialTheme.typography.titleLarge)
                    Text("Your latest 30 jobs will appear here.\nDocument content is never logged.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(history) { entry ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(10.dp)); BadgeLabel(entry.result)
                        }
                        Text("${entry.pages} page data ${if (entry.result == "EXPORTED") "exported" else "sent"} · ${entry.time}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(options: PrintOptions, locked: Boolean, insecure: Boolean, dynamic: Boolean, model: MainViewModel, onClear: () -> Unit, onDiagnostics: () -> Unit, onPrintSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column { Eyebrow("MAKE IT YOURS"); Spacer(Modifier.height(9.dp)); Text("Small details.\nBetter pages.", style = MaterialTheme.typography.headlineMedium) }
        SectionHeading("Printer defaults")
        PrintLayoutOptions(options, !locked, model::options)
        Text("Heat density", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..3).forEach { level -> FilterChip(options.density == level, onClick = { model.options(options.copy(density = level)) }, enabled = !locked, label = { Text(if (level == 0) "0 · default" else level.toString()) }) }
        }
        Text("Start at 0. Higher density uses more heat. The usable density range has not been hardware verified.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Paper feed", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(options.feed == Feed.CONTINUOUS, onClick = { model.options(options.copy(feed = Feed.CONTINUOUS)) }, enabled = !locked, label = { Text("Continuous") })
            FilterChip(options.feed == Feed.LABEL, onClick = { model.options(options.copy(feed = Feed.LABEL)) }, enabled = !locked, label = { Text("Label / locate") })
        }
        Text("Continuous sends a short feed. Label mode sends the vendor’s page-location command; do not use it with unmarked roll paper unless supported by your printer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionHeading("Connection & appearance")
        ToggleRow("Compatibility fallback", "If secure SPP fails, also try insecure SPP. Off by default; use only with your own nearby printer.", insecure, !locked, model.rt.store::setInsecure)
        ToggleRow("Use wallpaper colours", "Android Material You dynamic colour", dynamic, true, model.rt.store::setDynamicColors)
        OutlinedButton(onClick = onPrintSettings, modifier = Modifier.fillMaxWidth()) { Text("Android print-service settings") }
        HorizontalDivider()
        SectionHeading("Your files stay yours")
        Text("No internet permission. No accounts, ads, analytics or cloud uploads. Imported documents are copied to private cache; job history stores only titles, outcomes and counts. Backups are disabled.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClear, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text("Clear imported files") }
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Icon(Symbols.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save redacted diagnostics") }
        Text("Diagnostics exclude document names, contents, Bluetooth addresses and printer identifiers. Review the JSON before sharing it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionHeading("A4DY Print ${BuildConfig.VERSION_NAME}")
        Text("Independent, original Kotlin + Material 3 app. MIT-licensed source. Not affiliated with the printer manufacturer or HyPrint.\n\nAndroid 12 or newer · 203 dpi monochrome\nPDF, images, notes and Android system printing.\n\nScanner, OCR, web capture and vendor templates are not included in this first release.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Notice("Initial physical test reported", "A user successfully paired, connected, printed a test strip and used Android system printing on firmware v6.0.4. This is not a full device test matrix; calibration and advanced behavior remain unverified.")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun JobPanel(progress: JobProgress, pending: Boolean, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (pending && !progress.running) "Starting print job…" else progress.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    if (progress.running) Text(progress.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (progress.running) TextButton(onClick = onCancel) { Text("Cancel") }
                else if (!pending) IconButton(onClick = onDismiss) { Icon(Symbols.Close, "Dismiss job result") }
            }
            if (progress.running || pending) LinearProgressIndicator(progress = { if (pending) 0f else progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun PrinterSummary(printer: SavedPrinter?, link: LinkState, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Icon(Symbols.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(printer?.name ?: "A printer to call your own", style = MaterialTheme.typography.titleSmall)
                Text(if (printer == null) "Connect your HZ-A4DY to get started" else link.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Symbols.Arrow, "Printer settings", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ActionTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun StepRow(number: String, title: String, description: String) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(number, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun Eyebrow(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.primary) }
@Composable private fun SectionHeading(text: String, modifier: Modifier = Modifier) { Text(text, modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
@Composable private fun BadgeLabel(text: String, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, letterSpacing = .6.sp)
}
@Composable private fun Notice(title: String, message: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(3.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, enabled = enabled)
    }
}
@Composable private fun IntSlider(label: String, value: Int, range: IntRange, enabled: Boolean, suffix: String = "", onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 7.dp)) {
        Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("$value${if (suffix.isNotBlank()) " $suffix" else ""}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Slider(value.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(range)) }, enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
    }
}
@Composable private fun ExpandableSection(title: String, summary: String, initiallyOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionHeading(title); Spacer(Modifier.height(3.dp)); Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(if (open) Symbols.Minus else Symbols.Plus, if (open) "Collapse $title" else "Expand $title")
        }
        AnimatedVisibility(open) { Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), content = content) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
@Composable private fun NoteDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("A little thought, on paper.", style = MaterialTheme.typography.headlineMedium) },
        text = { OutlinedTextField(text, onValueChange = { text = it.take(100_000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp), label = { Text("Your note") }, supportingText = { Text("Created as a local A4 PDF · up to 100,000 characters") }, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onCreate(text) }) { Text("Preview note") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable private fun ManualPrinterDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("HZ-A4DY") }
    var address by rememberSaveable { mutableStateOf("") }
    val valid = BluetoothAdapter.checkBluetoothAddress(address.trim().uppercase())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add printer address") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("For a compatible DY Classic SPP printer. You can find its address in Android Bluetooth settings.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Printer name") }, singleLine = true)
            OutlinedTextField(address, { address = it.take(17) }, label = { Text("Bluetooth address") }, placeholder = { Text("AA:BB:CC:DD:EE:FF") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), isError = address.isNotBlank() && !valid)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(name, address) }) { Text("Save printer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
