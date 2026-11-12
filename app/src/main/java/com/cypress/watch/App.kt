package com.cypress.watch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Prefs.init(this)
        ProtoLog.init(this)
        com.cypress.watch.face.Faces.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LINK, "Watch connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps the watch connected in the background"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Find my phone", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        lateinit var ctx: Context
        const val CH_LINK = "link"
        const val CH_ALERT = "alert"
    }
}
