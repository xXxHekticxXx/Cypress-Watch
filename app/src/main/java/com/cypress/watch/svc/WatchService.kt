package com.cypress.watch.svc

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cypress.watch.App
import com.cypress.watch.Prefs
import com.cypress.watch.R
import com.cypress.watch.ble.Proto
import com.cypress.watch.ble.Watch
import com.cypress.watch.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the watch connected in the background and syncs it on a schedule. */
class WatchService : Service() {
    private var loop: Job? = null
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Sync.init(this)
        val n = note("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(1, n)
        statusJob = Watch.scope.launch {
            Watch.state.collectLatest { s ->
                val text = when (s.link) {
                    Watch.Link.READY -> "Connected" + (s.battery?.let { " · watch battery $it%" } ?: "")
                    Watch.Link.CONNECTING -> "Looking for your watch…"
                    Watch.Link.SCANNING -> "Searching…"
                    Watch.Link.OFF -> "Not connected"
                }
                getSystemService(android.app.NotificationManager::class.java).notify(1, note(text))
            }
        }
        loop = Watch.scope.launch {
            var tick = 0
            while (isActive) {
                delay(60_000)
                tick++
                if (!Watch.ready) { if (Prefs.address != null && Watch.state.value.link == Watch.Link.OFF) Watch.connect(); continue }
                if (tick % 10 == 0) Watch.send(Proto.battery())
                if (tick % 30 == 0) Sync.syncHistory()
                if (tick % 60 == 0) { WeatherSync.push(); if (Prefs.syncTime) Watch.send(Proto.setTime()) }
            }
        }
        if (Prefs.address != null && !Watch.ready) Watch.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RING) { FindPhone.set(this, false); Watch.send(Proto.findWatch(false)) }
        return START_STICKY
    }

    override fun onDestroy() { loop?.cancel(); statusJob?.cancel(); super.onDestroy() }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, App.CH_LINK).setSmallIcon(R.drawable.ic_stat).setContentTitle("Cypress Watch")
            .setContentText(text).setOngoing(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
    }

    companion object {
        const val ACTION_STOP_RING = "stop_ring"
        fun start(c: Context) {
            if (Prefs.address == null) return
            runCatching { ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java)) }
        }
    }
}
