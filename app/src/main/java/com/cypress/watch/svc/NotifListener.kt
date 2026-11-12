package com.cypress.watch.svc

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cypress.watch.Prefs
import java.util.Calendar

/** Forwards phone notifications (calls, texts, chosen apps) to the watch. Also gives access to music sessions. */
class NotifListener : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        Sync.init(this)
        Media.start(this)
        WatchService.start(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val isCall = n.category == Notification.CATEGORY_CALL
        if (sbn.isOngoing && !isCall) return
        val isText = n.category == Notification.CATEGORY_MESSAGE && SMS_APPS.any { sbn.packageName.startsWith(it) }
        val allowed = when {
            isCall -> Prefs.forwardCalls
            isText -> Prefs.forwardTexts
            else -> Prefs.appEnabled(sbn.packageName)
        }
        if (!allowed || quiet()) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        // the same notification gets re-posted often (progress, edits): only send real changes, once every few seconds
        val sig = "${sbn.packageName}|$title|$text"
        val now = System.currentTimeMillis()
        if ((recent[sig] ?: 0) > now - 15000) return
        recent[sig] = now
        if (recent.size > 60) recent.remove(recent.keys.first())

        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault("")
        if (isCall) Sync.notifyWatch("Incoming call", title.ifBlank { text }, app)
        else Sync.notifyWatch(title, text, app)
    }

    private fun quiet(): Boolean {
        if (!Prefs.quietHours) return false
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val a = Prefs.quietFrom; val b = Prefs.quietTo
        return if (a <= b) m in a until b else m >= a || m < b
    }

    companion object {
        val SMS_APPS = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging")
    }
}
