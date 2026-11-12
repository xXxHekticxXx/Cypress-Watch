package com.cypress.watch.svc

import android.content.Context
import android.media.AudioManager
import com.cypress.watch.Alarm
import com.cypress.watch.Prefs
import com.cypress.watch.ProtoLog
import com.cypress.watch.ble.History
import com.cypress.watch.ble.Proto
import com.cypress.watch.ble.Watch
import com.cypress.watch.data.HealthDb
import com.cypress.watch.face.Faces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the app does with the watch: reacts to what it sends and pushes phone data to it. */
object Sync {
    lateinit var db: HealthDb
    private lateinit var ctx: Context
    /** Bumps whenever new health data was stored, so the screens refresh. */
    val dataVersion = MutableStateFlow(0)
    /** Faces installed on the watch (id, version, type) from the last face list reply. */
    val faces = MutableStateFlow<List<Face>>(emptyList())
    data class Face(val id: Long, val version: String, val type: Int)

    private val waiting = HashMap<String, CompletableDeferred<List<ByteArray>>>()

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        db = HealthDb(ctx)
        Watch.init(ctx)
        Watch.packetListeners += ::onPacket
        Watch.groupListeners += ::onGroup
        Watch.onReady = { Watch.scope.launch { onConnected() } }
    }

    private suspend fun onConnected() {
        val m = Watch.maxPacket
        Watch.send(Proto.getAll(Proto.INFO))
        Watch.send(Proto.enableFindPhone())
        Watch.send(Proto.enableNotifications(m))
        if (Prefs.syncTime) Watch.send(Proto.setTime())
        Watch.send(Proto.faceInfo())
        delay(800)
        if (Prefs.alarms.isEmpty()) Watch.send(Proto.getAll(Proto.ALARM))
        Watch.send(Proto.getAll(Proto.DAY))
        if (Prefs.weather) WeatherSync.pushNow()
        Media.pushCurrent()
        delay(1500)
        syncHistory()
    }

    // ---------- watch -> phone ----------
    private fun onPacket(p: ByteArray) {
        val cmd = Proto.u8(p, 1); val mode = Proto.u8(p, 2)
        when {
            // battery: 01 a4 aa 16 <len> <01 01 lvl 02 01 chg>   or pushed 01 a4 ac <f> <len> <blob>
            cmd == 0xa4 && p.size >= 6 && ((mode == 0xaa && Proto.u8(p, 3) == 0x16) || mode == 0xac) -> {
                val len = Proto.u8(p, 4)
                if (p.size >= 5 + len) battery(p.copyOfRange(5, 5 + len))
            }
            // find phone: 01 a5 ac 02 01 <on>
            cmd == 0xa5 && mode == 0xac && p.size >= 6 && Proto.u8(p, 3) == 0x02 -> FindPhone.set(ctx, Proto.u8(p, 5) != 0)
            // music buttons: 01 e2 ac 01 02 <btn>
            cmd == 0xe2 && mode == 0xac && p.size >= 6 && Proto.u8(p, 3) == 0x01 && Proto.u8(p, 4) == 0x02 -> Media.button(Proto.u8(p, 5))
        }
    }

    private fun onGroup(cmd: Int, mode: Int, f: Int, packets: List<ByteArray>) {
        val key = "$cmd/$mode/$f"
        waiting.remove(key)?.complete(packets)
        val data = Watch.joined(packets)
        when {
            cmd == 0xa4 && mode == 0xaa && f == 0xaa -> deviceInfo(data)
            cmd == 0xc3 && mode == 0xaa && f == 0xaa -> daySummary(data)
            cmd == 0xc7 && mode == 0xaa && f == 0xaa -> alarmsFromWatch(data)
            cmd == 0xe3 && mode == 0xaa && f == 0xaa -> faceInfo(data)
            cmd == 0xe3 && mode == 0xaa && f == 0x0b -> faceList(data)
        }
    }

    private fun ascii(v: ByteArray) = v.takeIf { a -> a.isNotEmpty() && a.all { it in 0x20..0x7e } }?.toString(Charsets.US_ASCII)

    private fun deviceInfo(d: ByteArray) {
        val t = Proto.tlv(d)
        val texts = t.mapValues { ascii(it.value) }
        ProtoLog.info("device info: " + texts.entries.joinToString { "%02x=%s".format(it.key, it.value ?: t[it.key]!!.joinToString("") { b -> "%02x".format(b) }) })
        val fw = texts.values.firstOrNull { it != null && it.startsWith("AT") && it.length >= 8 }
        val model = texts.values.firstOrNull { it != null && (it.startsWith("NAL") || it.contains("-WB")) }
        Watch.update { it.copy(firmware = fw ?: it.firmware, model = model ?: it.model) }
        t[0x16]?.let { battery(it) }
    }

    private fun battery(blob: ByteArray) {
        val t = Proto.innerTlv(blob)
        val lvl = Proto.num(t[0x01]) ?: return
        if (lvl in 0..100) Watch.update { it.copy(battery = lvl, charging = (Proto.num(t[0x02]) ?: 0) != 0) }
    }

    private fun daySummary(d: ByteArray) {
        val t = Proto.tlv(d)
        val metrics = t[0x0c] ?: return
        val inner = Proto.innerTlv(metrics)
        ProtoLog.info("today: " + inner.entries.joinToString { "%02x=%d".format(it.key, Proto.num(it.value) ?: -1) })
        val steps = Proto.num(inner[0x05]) ?: return
        Watch.update { it.copy(stepsToday = steps) }
        db.putDay(Proto.midnight(), steps, null, null)
        dataVersion.value++
    }

    private fun alarmsFromWatch(d: ByteArray) {
        val parsed = sortedMapOf<Int, IntArray>()
        var cur: IntArray? = null
        var i = 0
        while (i + 3 <= d.size) {
            if (d[i] != Proto.GET) { i++; continue }
            val f = Proto.u8(d, i + 1); val len = Proto.u8(d, i + 2)
            if (i + 3 + len > d.size) break
            when (f) {
                1 -> { cur = intArrayOf(0, 0, 0, 0); parsed[Proto.u8(d, i + 3)] = cur }
                2 -> cur?.set(2, Proto.u8(d, i + 3))
                3 -> cur?.set(3, Proto.u8(d, i + 3))
                4 -> { cur?.set(0, Proto.u8(d, i + 3)); if (len >= 2) cur?.set(1, Proto.u8(d, i + 4)) }
            }
            i += 3 + len
        }
        if (parsed.isNotEmpty()) Prefs.alarms = parsed.values.map { Alarm(it[0], it[1], it[2], it[3] != 0) }
    }

    private fun faceInfo(d: ByteArray) {
        val t = Proto.tlv(d)
        val w = Proto.num(t[0x02]); val h = Proto.num(t[0x03])
        ProtoLog.info("face info: w=$w h=$h slot=${Proto.num(t[0x08])} fields=${t.keys.joinToString { "%02x".format(it) }}")
        if (w != null && h != null && w in 100..1000 && h in 100..1000) Watch.update { it.copy(screenW = w, screenH = h) }
        Faces.maxBytes = Proto.num(t[0x08])?.toLong() ?: Faces.maxBytes
        t[0x0b]?.let { faceList(byteArrayOf(Proto.GET, 0x0b, it.size.toByte()) + it) }
    }

    /** Face table entries: <version 5 ASCII "1.0.0"> <type> <flag> <id 3 bytes>. */
    private fun faceList(d: ByteArray) {
        val out = mutableListOf<Face>()
        val s = d
        var i = 0
        while (i + 10 <= s.size) {
            val v = s.copyOfRange(i, i + 5)
            if (v[1] == '.'.code.toByte() && v[3] == '.'.code.toByte()) {
                val type = Proto.u8(s, i + 5)
                val id = (Proto.u8(s, i + 7).toLong() shl 16) or (Proto.u8(s, i + 8).toLong() shl 8) or Proto.u8(s, i + 9).toLong()
                out += Face(id, v.toString(Charsets.US_ASCII), type)
                i += 10
            } else i++
        }
        if (out.isNotEmpty()) faces.value = out
        ProtoLog.info("faces on watch: " + out.joinToString { "%06x(t%d)".format(it.id, it.type) })
    }

    // ---------- phone -> watch ----------
    suspend fun request(p: ByteArray, key: String, timeoutMs: Long = 25000): List<ByteArray>? {
        val d = CompletableDeferred<List<ByteArray>>()
        waiting[key] = d
        Watch.send(p)
        return withTimeoutOrNull(timeoutMs) { d.await() }.also { waiting.remove(key) }
    }

    @Volatile var syncing = false
    suspend fun syncHistory(days: Int = 7) {
        if (syncing || !Watch.ready) return
        syncing = true
        Watch.update { it.copy(busy = "Syncing health data…") }
        try {
            val now = System.currentTimeMillis() / 1000
            val from = if (Prefs.lastSync > 0) maxOf(Prefs.lastSync - 3 * 3600, now - days * 86400L) else now - days * 86400L
            Watch.send(Proto.getAll(Proto.DAY))
            Watch.send(Proto.historyPrep(from, now))
            delay(500)
            val fit = request(Proto.historyFitness(from, now), "198/170/1")
            if (fit != null) {
                val slots = History.fitness(fit)
                ProtoLog.info("fitness: ${fit.size} packets -> ${slots.size} minutes")
                if (slots.isNotEmpty()) db.putSlots(slots)
            }
            val sl = request(Proto.historySleep(now - 7 * 86400L, now), "198/170/4")
            if (sl != null) {
                val parts = History.sleep(sl)
                ProtoLog.info("sleep: ${sl.size} packets -> ${parts.size} parts")
                if (parts.isNotEmpty()) db.putSleep(parts)
            }
            if (fit != null || sl != null) Prefs.lastSync = now
            dataVersion.value++
        } finally {
            syncing = false
            Watch.update { it.copy(busy = null) }
        }
    }

    fun pushAlarms() { if (Watch.ready) Watch.send(Proto.alarms(Prefs.alarms, Watch.maxPacket)) }

    fun notifyWatch(title: String, body: String, app: String, type: Int = 4) {
        if (!Watch.ready) return
        Watch.send(Proto.notification(title, body, app, type, Watch.maxPacket))
    }

    fun volume(): Pair<Int, Int> {
        val a = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) to a.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
}
