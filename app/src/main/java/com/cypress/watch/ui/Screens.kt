package com.cypress.watch.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cypress.watch.Alarm
import com.cypress.watch.Prefs
import com.cypress.watch.ProtoLog
import com.cypress.watch.Updater
import com.cypress.watch.ble.History
import com.cypress.watch.ble.Proto
import com.cypress.watch.ble.Watch
import com.cypress.watch.face.FaceDesigns
import com.cypress.watch.face.Faces
import com.cypress.watch.svc.Sync
import com.cypress.watch.svc.WeatherSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val nf = NumberFormat.getIntegerInstance()

@Composable
private fun Header(title: String, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = C.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
private fun ConnectionCard(a: MainActivity) {
    val s by Watch.state.collectAsState()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(C.Hover, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("⌚", fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name ?: Prefs.deviceName ?: "Watch", color = C.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val (txt, col) = when (s.link) {
                    Watch.Link.READY -> "● Connected" to C.Ok
                    Watch.Link.CONNECTING -> "● Connecting…" to C.Warn
                    Watch.Link.SCANNING -> "● Searching…" to C.Warn
                    Watch.Link.OFF -> "● Not connected" to C.Bad
                }
                Row {
                    Text(txt, color = col, fontSize = 12.sp)
                    if (Prefs.lastSync > 0) Text(" · synced " + ago(Prefs.lastSync), color = C.Muted, fontSize = 12.sp)
                }
            }
            s.battery?.let { Column(horizontalAlignment = Alignment.End) { Text("$it%", color = C.Ink, fontWeight = FontWeight.Bold); Text(if (s.charging) "charging" else "battery", color = C.Muted, fontSize = 12.sp) } }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (s.busy != null) "Syncing…" else "⟳ Sync now", enabled = s.busy == null) {
                if (Watch.ready) Watch.scope.launch { Sync.syncHistory() } else Watch.connect()
            }
            Btn("📳 Find watch", primary = false, enabled = s.link == Watch.Link.READY) {
                Watch.send(Proto.findWatch(true)); Watch.scope.launch { kotlinx.coroutines.delay(6000); Watch.send(Proto.findWatch(false)) }
            }
        }
        if (s.link == Watch.Link.OFF) Text("Tap Sync now to reconnect. If it won't connect, force-stop GloryFitPro and turn the watch's Bluetooth off and on.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() / 1000 - t) / 60
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}

@Composable
private fun dataVersion(): Int = Sync.dataVersion.collectAsState().value

@Composable
fun TodayScreen(a: MainActivity) {
    val v = dataVersion()
    val s by Watch.state.collectAsState()
    val today = Proto.midnight()
    val now = System.currentTimeMillis() / 1000
    val sum = remember(v) { Sync.db.daySum(today) }
    val hr = remember(v) { Sync.db.series("hr", today, now + 60) }
    val spo2 = remember(v) { Sync.db.series("spo2", now - 36 * 3600, now + 60) }
    val sleep = remember(v) { Sync.db.sleepParts(today - 14 * 3600, today + 14 * 3600).filter { it.stage in 1..3 } }
    val steps = maxOf(s.stepsToday ?: 0, sum.steps)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Cypress Watch")
        UpdateCard(a, onlyIfNew = true)
        ConnectionCard(a)
        if (!a.hasNotificationAccess()) Card(Modifier.clickable { a.openNotificationAccess() }) {
            Text("Turn on notification access", color = C.Warn, fontWeight = FontWeight.Bold)
            Text("Needed to show calls, texts and app alerts on the watch and to control music. Tap here, then switch Cypress Watch on.", color = C.Muted, fontSize = 13.sp)
        }
        Section("Today")
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("👣", "Steps", if (steps > 0) nf.format(steps) else "—", sub = "goal ${nf.format(Prefs.stepGoal)}", modifier = Modifier.weight(1f)) {
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).background(Color(0xFF2B313C), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth((steps / Prefs.stepGoal.toFloat()).coerceIn(0f, 1f)).fillMaxHeight().background(C.Accent, RoundedCornerShape(3.dp)))
                    }
                }
                Tile("❤️", "Heart rate", hr.lastOrNull()?.v?.toString() ?: "—", "bpm",
                    if (hr.isNotEmpty()) "low ${hr.minOf { it.v }} · high ${hr.maxOf { it.v }}" else "no readings yet", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val mins = sleep.sumOf { it.minutes }
                Tile("🌙", "Sleep", if (mins > 0) "${mins / 60}h ${mins % 60}m" else "—", sub = if (mins > 0) "deep ${sleep.filter { it.stage == 1 }.sumOf { it.minutes }.let { "${it / 60}h ${it % 60}m" }}" else "last night", modifier = Modifier.weight(1f))
                Tile("🩸", "Blood oxygen", spo2.lastOrNull()?.v?.toString() ?: "—", "%", if (spo2.isNotEmpty()) "avg ${spo2.map { it.v }.average().toInt()}%" else "no readings yet", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("🔥", "Calories", if (sum.cal > 0) nf.format(sum.cal) else "—", "kcal", modifier = Modifier.weight(1f))
                Tile("📏", "Distance", if (sum.distM > 0) "%.1f".format(sum.distM / 1609.34) else "—", "mi", modifier = Modifier.weight(1f))
            }
        }
        WeatherSync.last?.let { w ->
            val f = { c: Double -> if (Prefs.fahrenheit) "${Math.round(c * 9 / 5 + 32)}°" else "${Math.round(c)}°" }
            Card { Text("⛅ ${Prefs.weatherCity}: ${f(w.tempC)}, high ${f(w.highC)} / low ${f(w.lowC)}", color = C.Ink) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------- Health ----------------
@Composable
fun HealthScreen(a: MainActivity) {
    val v = dataVersion()
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Health")
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Steps", "Heart", "Sleep", "SpO₂").forEachIndexed { i, t ->
                Text(t, color = if (tab == i) C.Bg else C.Ink, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                    modifier = Modifier.background(if (tab == i) C.Ink else Color.Transparent, RoundedCornerShape(50)).border(1.dp, C.Line, RoundedCornerShape(50)).clickable { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        val today = Proto.midnight()
        val days = (6 downTo 0).map { Proto.midnight(it) }
        val dayLbl = SimpleDateFormat("EEE", Locale.US)
        when (tab) {
            0 -> {
                val vals = remember(v) { days.map { maxOf(Sync.db.daySum(it).steps, 0) } }
                Card {
                    Text("Last 7 days · avg ${nf.format(vals.average().toInt())} / day", color = C.Muted, fontSize = 13.sp)
                    Bars(vals, days.map { dayLbl.format(Date(it * 1000)) }, C.Accent, goal = Prefs.stepGoal)
                }
            }
            1 -> {
                val pts = remember(v) { Sync.db.series("hr", today, today + 86400) }
                Card {
                    Text("Heart rate today" + if (pts.isNotEmpty()) " · ${pts.minOf { it.v }}–${pts.maxOf { it.v }} bpm" else "", color = C.Muted, fontSize = 13.sp)
                    LineChart(pts.map { (it.ts - today) / 86400f to it.v.toFloat() }, Color(0xFFF87171))
                }
                val week = remember(v) { days.map { d -> Sync.db.series("hr", d, d + 86400).let { if (it.isEmpty()) 0 else it.minOf { p -> p.v } } } }
                Card { Text("Lowest heart rate each day", color = C.Muted, fontSize = 13.sp); Bars(week, days.map { dayLbl.format(Date(it * 1000)) }, Color(0xFFF87171)) }
            }
            2 -> {
                val nights = remember(v) { days.map { d -> Sync.db.sleepParts(d - 10 * 3600, d + 14 * 3600).filter { it.stage in 1..3 }.sumOf { it.minutes } } }
                Card { Text("Sleep per night (hours)", color = C.Muted, fontSize = 13.sp); Bars(nights.map { it / 60 }, days.map { dayLbl.format(Date(it * 1000)) }, Color(0xFFA78BFA)) }
                val last = remember(v) { Sync.db.sleepParts(today - 10 * 3600, today + 14 * 3600) }
                Card {
                    Text("Last night", color = C.Muted, fontSize = 13.sp)
                    if (last.isEmpty()) Text("No sleep data yet. Wear the watch overnight, then sync.", color = C.Muted)
                    for (stage in listOf(1, 2, 3, 4)) {
                        val m = last.filter { it.stage == stage }.sumOf { it.minutes }
                        if (m > 0) Text("${History.stageName(stage)}: ${m / 60}h ${m % 60}m", color = C.Ink)
                    }
                }
            }
            else -> {
                val pts = remember(v) { Sync.db.series("spo2", today - 6 * 86400, today + 86400) }
                Card {
                    Text("Blood oxygen, last 7 days", color = C.Muted, fontSize = 13.sp)
                    LineChart(pts.map { (it.ts - (today - 6 * 86400)) / (7 * 86400f) to it.v.toFloat() }, Color(0xFF60A5FA), min = 85f, max = 100f)
                }
            }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export everything", color = C.Muted, modifier = Modifier.weight(1f))
                Btn("Spreadsheet (CSV)", primary = false) {
                    Watch.scope.launch {
                        val f = withContext(Dispatchers.IO) { Sync.db.exportCsv(java.io.File(a.cacheDir, "cypress-watch-health.csv")) }
                        withContext(Dispatchers.Main) { a.share(f, "text/csv", "Export health data") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Bars(vals: List<Int>, labels: List<String>, color: Color, goal: Int? = null) {
    val mx = maxOf(vals.maxOrNull() ?: 0, goal ?: 0, 1).toFloat()
    Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 10.dp)) {
        val bw = size.width / vals.size
        vals.forEachIndexed { i, v ->
            val h = size.height * (v / mx)
            drawRoundRect(color, Offset(i * bw + bw * 0.18f, size.height - h), Size(bw * 0.64f, h), CornerRadius(10f, 10f))
        }
        goal?.let { val y = size.height * (1 - it / mx); drawLine(C.Muted, Offset(0f, y), Offset(size.width, y), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))) }
    }
    Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
}

@Composable
private fun LineChart(pts: List<Pair<Float, Float>>, color: Color, min: Float? = null, max: Float? = null) {
    if (pts.isEmpty()) { Text("No readings yet. Sync after wearing the watch for a while.", color = C.Muted, modifier = Modifier.padding(vertical = 24.dp)); return }
    val lo = min ?: (pts.minOf { it.second } - 5); val hi = max ?: (pts.maxOf { it.second } + 5)
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 10.dp)) {
        val path = Path()
        pts.forEachIndexed { i, (x, y) ->
            val px = x.coerceIn(0f, 1f) * size.width; val py = size.height * (1 - ((y - lo) / (hi - lo)).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(4f))
    }
}

// ---------------- Alerts ----------------
@Composable
fun AlertsScreen(a: MainActivity) {
    var refresh by remember { mutableStateOf(0) }
    val apps = remember {
        val pm = a.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
            .filter { it.first != a.packageName }.sortedBy { it.second.lowercase() }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Header("Alerts") { Btn("Send test") { Sync.notifyWatch("Cypress Watch", "Test notification from your phone ✓", "Cypress") } }
            if (!a.hasNotificationAccess()) Card(Modifier.clickable { a.openNotificationAccess() }) {
                Text("Notification access is off", color = C.Warn, fontWeight = FontWeight.Bold)
                Text("Tap here and switch on Cypress Watch, or nothing will reach the watch.", color = C.Muted, fontSize = 13.sp)
            }
            Card {
                key(refresh) {
                    ToggleRow("Phone calls", "caller's name on the watch", "📞", Prefs.forwardCalls) { Prefs.forwardCalls = it; refresh++ }
                    ToggleRow("Text messages", null, "💬", Prefs.forwardTexts) { Prefs.forwardTexts = it; refresh++ }
                }
            }
            Card {
                key(refresh) {
                    ToggleRow("Quiet hours", "${hm(Prefs.quietFrom)} – ${hm(Prefs.quietTo)}, nothing is sent", "🌙", Prefs.quietHours) { Prefs.quietHours = it; refresh++ }
                    if (Prefs.quietHours) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("From ${hm(Prefs.quietFrom)}", primary = false) { pickTime(a, Prefs.quietFrom) { Prefs.quietFrom = it; refresh++ } }
                        Btn("To ${hm(Prefs.quietTo)}", primary = false) { pickTime(a, Prefs.quietTo) { Prefs.quietTo = it; refresh++ } }
                    }
                }
            }
            Section("Apps")
        }
        items(apps, key = { it.first }) { (pkg, label) ->
            var on by remember { mutableStateOf(Prefs.appEnabled(pkg)) }
            Row(Modifier.padding(horizontal = 14.dp)) {
                Column(Modifier.background(C.Panel).padding(horizontal = 14.dp)) {
                    ToggleRow(label, null, null, on) { on = it; Prefs.setAppEnabled(pkg, it) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun hm(m: Int): String { val h = m / 60; val mm = m % 60; return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, mm, if (h < 12) "am" else "pm") }
private fun pickTime(a: MainActivity, cur: Int, set: (Int) -> Unit) = TimePickerDialog(a, { _, h, m -> set(h * 60 + m) }, cur / 60, cur % 60, false).show()

// ---------------- Faces ----------------
@Composable
fun FacesScreen(a: MainActivity) {
    val ui by Faces.ui.collectAsState()
    val s by Watch.state.collectAsState()
    val onWatch by Sync.faces.collectAsState()
    val current by Sync.current.collectAsState()
    val w = s.screenW ?: 240; val h = s.screenH ?: 284
    LaunchedEffect(s.link) { if (s.link == Watch.Link.READY) { Watch.send(Proto.faceInfo()); Watch.send(Proto.faceList()) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Watch faces")
        if (ui.message != null || ui.progress != null) Card {
            ui.message?.let { Text(it, color = C.Ink) }
            ui.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = C.Accent) }
        }
        Section("On your watch")
        Card {
            if (onWatch.isEmpty()) Text(if (s.link == Watch.Link.READY) "Reading the watch's face list…" else "Connect the watch to see its faces.", color = C.Muted)
            onWatch.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Face ${i + 1}", color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("#%x".format(f.id), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                    if (current == f.id) Text("In use", color = C.Ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    else Btn("Use", primary = false) { Faces.switchTo(f); Sync.current.value = f.id }
                }
            }
            Text("You can also switch faces on the watch: long-press the face.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Section("Cypress designs")
        Card {
            Text(ui.templateState, color = if (ui.templateReady) C.Muted else C.Warn, fontSize = 13.sp)
            if (!ui.templateReady) {
                Text("Each design is built on an official face made for your watch, so its size and file format match. Tap below while the watch is connected (needs internet).", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Btn("Get the base face", enabled = s.link == Watch.Link.READY) { Watch.scope.launch { Faces.loadCatalog() } }
            }
        }
        val rows = (0 until FaceDesigns.all.size).chunked(2)
        for (r in rows) Row(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in r) {
                val bmp = remember(i, ui.templateReady) { Faces.previewFor(i, w, h).asImageBitmap() }
                Column(Modifier.weight(1f).background(C.Panel, RoundedCornerShape(16.dp)).border(1.dp, C.Line, RoundedCornerShape(16.dp)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(bmp.width / bmp.height.toFloat()).background(Color.Black, RoundedCornerShape(14.dp)))
                    Text(FaceDesigns.all[i].name, color = C.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Btn("Install", enabled = ui.templateReady && ui.progress == null && s.link == Watch.Link.READY, modifier = Modifier.fillMaxWidth()) { Faces.installDesign(i) }
                }
            }
            if (r.size == 1) Spacer(Modifier.weight(1f))
        }
        LaunchedEffect(s.link) { if (s.link == Watch.Link.READY && ui.official.isEmpty()) Faces.loadCatalog() }
        if (ui.official.isEmpty()) {
            Section("Official faces for your watch")
            Card {
                Text("Official faces load from the GloryFit catalog while the watch is connected (needs internet).", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                Btn("Load official faces", enabled = s.link == Watch.Link.READY) { Watch.scope.launch { Faces.loadCatalog() } }
            }
        } else {
            Section("Official faces for your watch")
            for (o in ui.official.take(20)) Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UrlImage(o.previewUrl, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(o.title, color = C.Ink, modifier = Modifier.weight(1f))
                    Btn("Install", primary = false, enabled = ui.progress == null && s.link == Watch.Link.READY) { Faces.installOfficial(o) }
                }
            }
        }
        Card {
            Text("Installing a face writes to the watch. If one ever fails, the watch keeps its old faces. If it reports it's full, delete a downloaded face on the watch and try again. Tap Watch → Send log to me if anything goes wrong.", color = C.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun UrlImage(url: String, modifier: Modifier) {
    var img by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        img = withContext(Dispatchers.IO) { runCatching { java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.background(Color.Black, RoundedCornerShape(10.dp))) { img?.let { Image(it, null, Modifier.fillMaxSize()) } }
}

@Composable
fun UpdateCard(a: MainActivity, onlyIfNew: Boolean) {
    val u by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (u.latest == null && !u.checking) Updater.check() }
    val newer = Updater.available(a)
    if (onlyIfNew && !newer && u.progress == null) return
    Card {
        if (newer) {
            Text("Update available: ${u.latest}", color = C.Ok, fontWeight = FontWeight.Bold)
            Text("You have ${Updater.current(a)}. Installs over this one and keeps your data.", color = C.Muted, fontSize = 13.sp)
        } else {
            Text("Cypress Watch ${Updater.current(a)}", color = C.Ink, fontWeight = FontWeight.Bold)
            Text(when { u.checking -> "Checking for updates…"; u.latest != null -> "You have the newest version."; else -> "" }, color = C.Muted, fontSize = 13.sp)
        }
        u.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = C.Accent) }
        u.error?.let { Text(it, color = C.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (newer) Btn(if (u.progress != null) "Downloading…" else "⬇ Install update", enabled = u.progress == null) { scope.launch { Updater.install(a) } }
            else Btn(if (u.checking) "Checking…" else "Check for updates", primary = false, enabled = !u.checking) { scope.launch { Updater.check() } }
        }
    }
}

// ---------------- Watch ----------------
@Composable
fun WatchScreen(a: MainActivity, onLog: () -> Unit, forgot: () -> Unit) {
    val s by Watch.state.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherSync.Place>>(emptyList()) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Watch")
        key(refresh) {
            Card {
                ToggleRow("Time", "set from your phone every time it connects", "🕒", Prefs.syncTime) { Prefs.syncTime = it; if (it) Watch.send(Proto.setTime()); refresh++ }
                ToggleRow("Weather", "${Prefs.weatherCity} · 7-day", "⛅", Prefs.weather) { Prefs.weather = it; if (it) WeatherSync.pushNow(); refresh++ }
                ToggleRow("Music controls", "play / pause / skip from the watch", "🎵", Prefs.music) { Prefs.music = it; refresh++ }
                ToggleRow("Show °F in this app", null, "🌡", Prefs.fahrenheit) { Prefs.fahrenheit = it; refresh++ }
            }
            if (Prefs.weather) Card {
                Text("Weather location", color = C.Muted, fontSize = 13.sp)
                OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Search a town", color = C.Muted) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Ink, unfocusedTextColor = C.Ink, focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line))
                Row(Modifier.padding(top = 6.dp)) { Btn("Search", primary = false) { scope.launch { results = WeatherSync.search(search) } } }
                results.forEach { p ->
                    Text("${p.name}, ${p.region}", color = C.Ink, modifier = Modifier.fillMaxWidth().clickable {
                        Prefs.weatherCity = p.name; Prefs.weatherLat = p.lat; Prefs.weatherLon = p.lon; results = emptyList(); search = ""; WeatherSync.pushNow(); refresh++
                    }.padding(vertical = 8.dp))
                }
            }
            Section("Alarms")
            Card {
                val alarms = Prefs.alarms
                if (alarms.isEmpty()) Text("No alarms set.", color = C.Muted)
                alarms.forEachIndexed { i, al ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { pickTime(a, al.hour * 60 + al.minute) { t -> Prefs.alarms = alarms.toMutableList().also { it[i] = al.copy(hour = t / 60, minute = t % 60) }; Sync.pushAlarms(); refresh++ } }) {
                            Text(hm(al.hour * 60 + al.minute), color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row { listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { d, l ->
                                val on = al.days and (1 shl d) != 0
                                Text(l, color = if (on) C.Accent else C.Muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable {
                                    Prefs.alarms = alarms.toMutableList().also { it[i] = al.copy(days = al.days xor (1 shl d)) }; Sync.pushAlarms(); refresh++
                                }.padding(end = 10.dp, top = 2.dp))
                            } }
                        }
                        androidx.compose.material3.Switch(al.on, { on -> Prefs.alarms = alarms.toMutableList().also { it[i] = al.copy(on = on) }; Sync.pushAlarms(); refresh++ })
                        Text("✕", color = C.Muted, modifier = Modifier.clickable { Prefs.alarms = alarms.toMutableList().also { it.removeAt(i) }; Sync.pushAlarms(); refresh++ }.padding(start = 12.dp))
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    Btn("+ Add alarm", enabled = alarms.size < 10) { pickTime(a, 6 * 60) { t -> Prefs.alarms = alarms + Alarm(t / 60, t % 60, 0b0111110, true); Sync.pushAlarms(); refresh++ } }
                }
                Text("Tap the time to change it, tap days to choose them.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Section("Step goal")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Btn("−", primary = false) { Prefs.stepGoal = (Prefs.stepGoal - 1000).coerceAtLeast(1000); refresh++ }
                    Text(nf.format(Prefs.stepGoal) + " steps", color = C.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Btn("+", primary = false) { Prefs.stepGoal = Prefs.stepGoal + 1000; refresh++ }
                }
            }
            Section("Keep it connected")
            Card {
                ToggleRow("Notification access", if (a.hasNotificationAccess()) "on" else "off · tap to turn on", "🔔", a.hasNotificationAccess()) { a.openNotificationAccess() }
                ToggleRow("Run in the background", if (a.ignoringBattery()) "on" else "off · Samsung may cut the connection", "🔋", a.ignoringBattery()) { a.askBattery() }
            }
            Section("App updates")
            UpdateCard(a, onlyIfNew = false)
            Section("About")
            Card {
                Text("${s.name ?: Prefs.deviceName ?: "Watch"}${s.model?.let { " · $it" } ?: ""}", color = C.Ink, fontWeight = FontWeight.Bold)
                Text("firmware ${s.firmware ?: "—"}", color = C.Muted, fontSize = 12.sp)
                Text("screen ${s.screenW ?: "?"}×${s.screenH ?: "?"} · ${Prefs.address ?: ""}", color = C.Muted, fontSize = 12.sp)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Send log to me", primary = false) { a.shareLog() }
                    Btn("Show log", primary = false) { onLog() }
                }
                Row(Modifier.padding(top = 8.dp)) { Btn("Forget this watch", primary = false) { Watch.disconnect(forget = true); forgot() } }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun LogScreen(a: MainActivity, close: () -> Unit) {
    var lines by remember { mutableStateOf(ProtoLog.recent()) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { a.runOnUiThread { lines = ProtoLog.recent() } }
        ProtoLog.listeners += l
        onDispose { ProtoLog.listeners -= l }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Watch log") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Share", primary = false) { a.shareLog() }
                Btn("Clear", primary = false) { ProtoLog.clear() }
                Btn("Close") { close() }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), reverseLayout = true) {
            items(lines.reversed()) { Text(it, color = C.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}
