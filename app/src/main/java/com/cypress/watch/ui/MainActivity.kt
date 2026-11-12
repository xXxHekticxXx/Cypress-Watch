package com.cypress.watch.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.cypress.watch.Prefs
import com.cypress.watch.ProtoLog
import com.cypress.watch.ble.Watch
import com.cypress.watch.svc.NotifListener
import com.cypress.watch.svc.Sync
import com.cypress.watch.svc.WatchService

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPerms?.invoke(); onPerms = null }
    private var onPerms: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sync.init(this)
        if (Prefs.address != null) WatchService.start(this)
        setContent {
            CypressTheme {
                var hasWatch by remember { mutableStateOf(Prefs.address != null) }
                Box(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
                    if (!hasWatch) SetupScreen(this@MainActivity) { hasWatch = true }
                    else Main(this@MainActivity) { hasWatch = false }
                }
            }
        }
    }

    fun withPermissions(then: () -> Unit) {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) need += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        onPerms = then
        perms.launch(need.toTypedArray())
    }

    fun hasNotificationAccess() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    fun openNotificationAccess() {
        val i = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, NotifListener::class.java).flattenToString())
        else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        runCatching { startActivity(i) }.onFailure { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
    }
    fun ignoringBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    @android.annotation.SuppressLint("BatteryLife")
    fun askBattery() = runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }

    fun share(file: java.io.File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "com.cypress.watch.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), title))
    }
    fun shareLog() {
        val f = java.io.File(cacheDir, "cypress-watch-log.txt"); ProtoLog.file().copyTo(f, overwrite = true)
        share(f, "text/plain", "Send the watch log")
    }
}

@Composable
fun SetupScreen(a: MainActivity, done: () -> Unit) {
    val found by Watch.found.collectAsState()
    val st by Watch.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Cypress Watch", color = C.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Before you start:", color = C.Ink, fontWeight = FontWeight.SemiBold)
        Text("1. Force-stop GloryFitPro (hold its icon → App info → Force stop) so it lets go of the watch.\n" +
            "2. Keep the watch close to the phone with Bluetooth on.\n3. Tap Find my watch and pick it (W60-…).", color = C.Muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(16.dp))
        Btn(if (st.link == Watch.Link.SCANNING) "Searching…" else "Find my watch") { a.withPermissions { Watch.startScan() } }
        st.error?.let { Text(it, color = C.Bad, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f)) {
            found.take(15).forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(C.Panel, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .clickable { Watch.connect(f.address, f.name); WatchService.start(a); done() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (Watch.looksLikeWatch(f.name)) "⌚" else "📶", fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = C.Ink, fontWeight = FontWeight.SemiBold)
                        Text(f.address, color = C.Muted, fontSize = 12.sp)
                    }
                    if (Watch.looksLikeWatch(f.name)) Text("This one", color = C.Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun Main(a: MainActivity, forgot: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (showLog) LogScreen(a) { showLog = false }
            else when (tab) {
                0 -> TodayScreen(a)
                1 -> HealthScreen(a)
                2 -> AlertsScreen(a)
                3 -> FacesScreen(a)
                else -> WatchScreen(a, onLog = { showLog = true }, forgot = forgot)
            }
        }
        Row(Modifier.fillMaxWidth().background(C.Bg2).padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("⌂" to "Today", "📈" to "Health", "🔔" to "Alerts", "🎨" to "Faces", "⌚" to "Watch").forEachIndexed { i, (ic, l) ->
                Column(Modifier.clickable { tab = i; showLog = false }.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ic, fontSize = 20.sp)
                    Text(l, color = if (tab == i && !showLog) C.Accent else C.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}
