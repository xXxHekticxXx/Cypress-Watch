package com.cypress.watch.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.cypress.watch.ProtoLog
import com.cypress.watch.ble.Proto
import com.cypress.watch.ble.Watch
import com.cypress.watch.svc.Sync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Watch faces: switching installed faces, installing official ones from the GloryFit catalog, and making the 20
 * Cypress designs. A Cypress design is built from an official face made for this exact watch (so the file
 * format, element layout and screen size match what the watch expects) with the background, preview and
 * digit colours replaced.
 */
object Faces {
    @Volatile var maxBytes: Long = 0
    private lateinit var dir: File

    data class Official(val id: String, val title: String, val previewUrl: String, val binUrl: String, val size: Long)
    data class Ui(
        val templateState: String = "Not loaded yet",
        val templateReady: Boolean = false,
        val official: List<Official> = emptyList(),
        val progress: Float? = null,
        val message: String? = null,
    )
    val ui = MutableStateFlow(Ui())
    private var template: ByteArray? = null
    private val previews = HashMap<Int, Bitmap>()

    fun init(c: Context) { dir = File(c.filesDir, "faces").apply { mkdirs() }; File(dir, "template.bin").takeIf { it.exists() }?.let { load(it.readBytes(), "saved") } }

    private fun load(b: ByteArray, from: String): Boolean {
        val f = runCatching { FaceFile(b) }.getOrElse { ProtoLog.info("template ($from) unreadable: ${it.message}"); return false }
        if (f.item(17) == null) { ProtoLog.info("template ($from) has no background element"); return false }
        template = b; previews.clear()
        ui.value = ui.value.copy(templateState = "Using a ${f.width}×${f.height} official face as the base (format v${f.version})", templateReady = true)
        return true
    }

    // ---------- catalog (the same server the GloryFitPro app uses) ----------
    private const val BASE = "https://www.ute-tech.com.cn/ci-yc/index.php/api/client/"
    private const val APPKEY = "75662fe33c4ee9d582b611eac9599ae5"

    private fun post(endpoint: String, body: JSONObject): JSONObject? = runCatching {
        val c = URL(BASE + endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(("content=" + URLEncoder.encode(body.toString(), "UTF-8")).toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        ProtoLog.info("catalog $endpoint -> ${text.take(300)}")
        JSONObject(text)
    }.onFailure { ProtoLog.info("catalog $endpoint failed: $it") }.getOrNull()

    private fun download(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.inputStream.use { it.readBytes() }
    }.onFailure { ProtoLog.info("download failed $url: $it") }.getOrNull()

    /** Every JSON object anywhere in the reply that looks like a face (has a .bin "resource"). */
    private fun facesIn(j: Any?, out: MutableList<Official>) {
        when (j) {
            is JSONObject -> {
                val res = j.optString("resource")
                if (res.startsWith("http")) out += Official(j.optString("id"), j.optString("title", "Face"), j.optString("preview", j.optString("image")), res, j.optLong("capacity"))
                j.keys().forEach { facesIn(j.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until j.length()) facesIn(j.opt(i), out)
        }
    }
    private fun classIds(j: Any?, out: MutableSet<String>) {
        when (j) {
            is JSONObject -> { listOf("id", "classId", "class_id").forEach { k -> j.optString(k).takeIf { it.matches(Regex("\\d{3,}")) }?.let { out += it } }; j.keys().forEach { classIds(j.opt(it), out) } }
            is JSONArray -> for (i in 0 until j.length()) classIds(j.opt(i), out)
        }
    }

    suspend fun loadCatalog() = withContext(Dispatchers.IO) {
        val s = Watch.state.value
        val w = s.screenW; val h = s.screenH
        if (w == null || h == null) { ui.value = ui.value.copy(message = "Connect the watch first so the app can read its screen size."); return@withContext }
        ui.value = ui.value.copy(templateState = "Looking up faces made for this watch…", message = null)
        val fw = s.firmware ?: ""
        val names = listOfNotNull(fw.take(6).ifBlank { null }, fw.take(5).ifBlank { null }, fw.take(8).ifBlank { null }, s.name?.substringBefore('-'), "W60").distinct()
        val mac = (com.cypress.watch.Prefs.address ?: "").replace(":", "")
        val found = mutableListOf<Official>()
        loop@ for (bt in names) {
            val classes = mutableSetOf<String>()
            classIds(post("getWatchClass", JSONObject().put("appkey", APPKEY).put("btname", bt).put("useOf", "0").put("language", "en")), classes)
            classes += listOf("100020", "100030")
            for (cls in classes.take(6)) for (dpi in listOf("$w*$h", "$h*$w", "$w*$w")) for (shape in listOf("1", "2", "0")) {
                val r = post("getWatchsByClass", JSONObject().put("appkey", APPKEY).put("btname", bt).put("mac", mac).put("type", "0").put("compatible", "2")
                    .put("shape", shape).put("sort", "1").put("start", "0").put("end", "30").put("class", cls).put("dpi", dpi).put("platform", "0")
                    .put("maxCapacity", if (maxBytes > 0) maxBytes.toString() else "999999999").put("language", "en").put("angle", "0").put("useOf", "0"))
                facesIn(r, found)
                if (found.size >= 12) break@loop
            }
            if (found.isNotEmpty()) break
        }
        val list = found.distinctBy { it.binUrl }
        ui.value = ui.value.copy(official = list)
        if (list.isEmpty()) { ui.value = ui.value.copy(templateState = "No faces for this watch were found in the catalog (see the log)."); return@withContext }
        if (template == null) {
            for (o in list.sortedBy { it.size.takeIf { s -> s > 0 } ?: Long.MAX_VALUE }.take(8)) {
                val b = download(o.binUrl) ?: continue
                if (load(b, o.title)) { File(dir, "template.bin").writeBytes(b); break }
            }
            if (template == null) ui.value = ui.value.copy(templateState = "Found ${list.size} official faces, but none could be used as a base (see the log).")
        }
    }

    // ---------- Cypress designs ----------
    private fun digitBox(f: FaceFile): RectF? {
        val hr = f.item(4) ?: return null; val mn = f.item(5)
        val right = (mn?.let { it.x + it.width * 2 } ?: (hr.x + hr.width * 4)).toFloat()
        return RectF(hr.x.toFloat(), hr.y.toFloat(), right.coerceAtMost(f.width.toFloat()), (hr.y + hr.height).toFloat())
    }

    fun previewFor(i: Int, w: Int, h: Int): Bitmap = previews.getOrPut(i) {
        val f = template?.let { runCatching { FaceFile(it) }.getOrNull() }
        FaceDesigns.preview(i, f?.width ?: w, f?.height ?: h, f?.let { digitBox(it) })
    }

    fun build(i: Int): ByteArray {
        val b = template ?: error("no base face yet")
        val f = FaceFile(b)
        val bg = f.item(17)!!
        val bmp = FaceDesigns.background(i, bg.width, bg.height)
        val px = IntArray(bg.width * bg.height); bmp.getPixels(px, 0, bg.width, 0, 0, bg.width, bg.height)
        f.replaceFrame(17, 0, px)
        f.item(10)?.let { pv ->
            val full = FaceDesigns.preview(i, f.width, f.height, digitBox(f))
            val small = Bitmap.createScaledBitmap(full, pv.width, pv.height, true)
            val sp = IntArray(pv.width * pv.height); small.getPixels(sp, 0, pv.width, 0, 0, pv.width, pv.height)
            f.replaceFrame(10, 0, sp)
        }
        f.tintAll(FaceDesigns.all[i].accent)
        val out = f.build()
        // sanity check: what we made must read back
        FaceFile(out).items.forEach { require(FaceFile(out).decode(it) != null || f.version >= 3) { "rebuilt face doesn't decode" } }
        return out
    }

    // ---------- switching ----------
    fun switchTo(face: Sync.Face) {
        Watch.send(Proto.faceApply(face.id, face.version, 0))
        Watch.scope.launch { delay(1200); Watch.send(Proto.faceList()) }
    }

    // ---------- uploading (group E3) ----------
    private var job: Job? = null

    fun installDesign(i: Int) = install("Cypress · ${FaceDesigns.all[i].name}") { build(i) }
    fun installOfficial(o: Official) = install(o.title) { download(o.binUrl) ?: error("download failed") }

    private fun install(label: String, make: suspend () -> ByteArray) {
        if (job?.isActive == true) return
        job = Watch.scope.launch {
            try {
                if (!Watch.ready) error("Connect the watch first")
                ui.value = ui.value.copy(progress = 0f, message = "Preparing $label…")
                val bytes = withContext(Dispatchers.Default) { make() }
                if (maxBytes > 0 && bytes.size > maxBytes) error("This face is too big for the watch (${bytes.size / 1024} KB, max ${maxBytes / 1024} KB)")
                upload(bytes, label)
            } catch (e: Exception) {
                ProtoLog.info("face install failed: $e")
                ui.value = ui.value.copy(progress = null, message = "Couldn't install: ${e.message}")
            }
        }
    }

    private class Stopped(val code: Int) : Exception("the watch stopped the transfer (code $code)")

    private suspend fun upload(data: ByteArray, label: String) {
        // a damaged piece makes the watch abort with code 4; start again from scratch a couple of times
        for (attempt in 1..3) {
            try { uploadOnce(data, label, attempt); return }
            catch (e: Stopped) { if (e.code != 4 || attempt == 3) throw e; ProtoLog.info("checksum error from watch, restarting upload"); delay(2000) }
        }
    }

    private suspend fun uploadOnce(data: ByteArray, label: String, attempt: Int) {
        val before = Sync.faces.value.map { it.id }.toSet()
        val events = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val l: (ByteArray) -> Unit = { p -> if (p.size >= 4 && (p[1].toInt() and 0xff) == 0xe3) events.trySend(p) }
        Watch.packetListeners += l
        fun u(p: ByteArray, i: Int) = p[i].toInt() and 0xff
        // status from the watch: 01 e3 ac 02 01 <code>  (2 ready, 4 checksum error, 5 too big, 6 resend from <hi lo>, 7 keep going, 8 full)
        fun status(p: ByteArray) = if (p.size >= 6 && u(p, 2) == 0xac && u(p, 3) == 0x02) u(p, 5) else null
        try {
            ProtoLog.info("face upload: $label ${data.size} bytes (try $attempt)")
            Watch.send(Proto.faceUploadStart())
            val start = withTimeoutOrNull(8000) { var r: ByteArray; do { r = events.receive() } while (status(r) == null); r }
            when (val code = start?.let { status(it) }) {
                null -> ProtoLog.info("no start reply; trying anyway")
                5 -> error("the watch says the face is too big")
                8 -> error("the watch is full. On the watch, long-press the face, delete a downloaded face, then try again")
                2 -> {}
                else -> ProtoLog.info("start reply code $code; continuing")
            }
            val slice = (Watch.maxPacket - 5).coerceAtLeast(15)
            val chunks = (data.size + slice - 1) / slice
            fun frame(k: Int) = Proto.b(1, 0xe3, 0xab, 0x01, k and 0xff) + data.copyOfRange(k * slice, minOf(data.size, (k + 1) * slice))
            var i = 0
            var tries = 0
            while (i < chunks) {
                Watch.send(frame(i))
                // wait for the watch to confirm exactly this piece: 01 e3 ab 01 <index>
                var next: Int? = null
                withTimeoutOrNull(4000) {
                    while (next == null) {
                        val r = events.receive()
                        val st = status(r)
                        when {
                            st == 6 && r.size >= 8 -> next = (u(r, 6) shl 8) or u(r, 7)
                            st == 4 || st == 5 || st == 8 -> throw Stopped(st)
                            st != null -> {} // 7 = keep going: not a confirmation
                            r.size >= 5 && u(r, 2) == 0xab && u(r, 3) == 0x01 && u(r, 4) == (i and 0xff) -> next = i + 1
                        }
                    }
                }
                if (next == null) {
                    if (++tries > 3) error("the watch stopped answering at ${i * 100 / chunks}%")
                    ProtoLog.info("no confirmation for piece $i, sending it again"); continue
                }
                tries = 0
                i = next!!
                if (i % 4 == 0) ui.value = ui.value.copy(progress = i / chunks.toFloat(), message = "Sending $label… ${i * 100 / chunks}%")
            }
            // closing packet carries the xor of every byte of every piece
            var xor = 0
            for (k in 0 until chunks) for (v in frame(k)) xor = xor xor (v.toInt() and 0xff)
            Watch.send(Proto.b(1, 0xe3, 0xab, 0x01, 0xfd, xor))
            ui.value = ui.value.copy(progress = 1f, message = "Finishing…")
            val end = withTimeoutOrNull(15000) { var r: ByteArray; do { r = events.receive() } while (!((r.size >= 5 && u(r, 4) == 0xfd) || status(r) in listOf(4, 5, 8))); r }
            end?.let { status(it) }?.takeIf { it in listOf(4, 5, 8) }?.let { throw Stopped(it) }
            ProtoLog.info("upload finished: ${end?.joinToString(" ") { "%02x".format(it) } ?: "no closing reply"}")
            delay(1500)
            Watch.send(Proto.faceList())
            delay(2500)
            val new = Sync.faces.value.firstOrNull { it.id !in before }
            if (new != null) { Watch.send(Proto.faceApply(new.id, new.version, 1)); Sync.current.value = new.id }
            ui.value = ui.value.copy(progress = null, message = if (new != null) "$label is on your watch." else "$label was sent. If it isn't showing, long-press the watch face and pick it.")
        } finally {
            Watch.packetListeners -= l
        }
    }
}
