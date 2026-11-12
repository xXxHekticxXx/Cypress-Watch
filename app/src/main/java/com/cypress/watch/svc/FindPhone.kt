package com.cypress.watch.svc

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.cypress.watch.App
import com.cypress.watch.R

/** "Find my phone" from the watch: rings loudly until stopped on the phone or the watch. */
object FindPhone {
    private var player: MediaPlayer? = null

    fun set(c: Context, on: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!on) { stop(); nm.cancel(7); return }
        if (player != null) return
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(c, uri); isLooping = true; prepare(); start()
            }
        }
        val stop = PendingIntent.getService(c, 7, Intent(c, WatchService::class.java).setAction(WatchService.ACTION_STOP_RING), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7, NotificationCompat.Builder(c, App.CH_ALERT).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Your watch is looking for this phone").setContentText("Tap to stop ringing")
            .setContentIntent(stop).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stop() }, 60000)
    }

    fun stop() { runCatching { player?.stop(); player?.release() }; player = null }
}
