package com.cypress.watch.svc

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.cypress.watch.Prefs
import com.cypress.watch.ble.Proto
import com.cypress.watch.ble.Watch

/** Music: shows what's playing on the watch and turns the watch's buttons into play/pause/skip/volume. */
object Media {
    private var ctx: Context? = null
    private var controller: MediaController? = null
    private var lastSent = ""

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushCurrent()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushCurrent()
    }

    fun start(c: Context) {
        ctx = c.applicationContext
        val msm = c.getSystemService(MediaSessionManager::class.java)
        val comp = ComponentName(c, NotifListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener({ list -> pick(list) }, comp)
            pick(msm.getActiveSessions(comp))
        }
    }

    private fun pick(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(cb)
        controller = c
        c?.registerCallback(cb)
        pushCurrent()
    }

    fun pushCurrent() {
        if (!Prefs.music || !Watch.ready) return
        val c = controller
        val md = c?.metadata
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val (max, vol) = Sync.volume()
        val sig = "$artist|$title|$playing|$vol"
        if (sig == lastSent) return
        lastSent = sig
        Watch.send(Proto.musicInfo(artist, title, playing, max, vol, Watch.maxPacket))
    }

    /** Watch buttons: 1 play, 2 pause, 3 previous, 4 next, 5 volume up, 6 volume down. */
    fun button(b: Int) {
        val t = controller?.transportControls
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        when (b) {
            1 -> t?.play()
            2 -> t?.pause()
            3 -> t?.skipToPrevious()
            4 -> t?.skipToNext()
            5 -> am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            6 -> am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
        lastSent = ""
        Watch.scope.run { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pushCurrent() }, 400) }
    }
}
