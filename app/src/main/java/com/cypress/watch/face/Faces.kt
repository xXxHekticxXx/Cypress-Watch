package com.cypress.watch.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.cypress.watch.ProtoLog
import com.cypress.watch.hex
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
    /** How many downloaded faces the watch holds at once (face info field 09). */
    @Volatile var slots: Int = 0
    private lateinit var dir: File

    data class Official(val id: String, val title: String, val previewUrl: String, val binUrl: String, val size: Long)
    data class Ui(
        val templateState: String = "Not loaded yet",
        val templateReady: Boolean = false,
        val official: List<Official> = emptyList(),
        val progress: Float? = null,
        val message: String? = null,
        /** Set when the watch's download slots are full: the user picks a face to replace. */
        val needRoom: List<Sync.Face>? = null,
    )
    val ui = MutableStateFlow(Ui())
    private var template: ByteArray? = null
    private val previews = HashMap<Int, Bitmap>()

    fun init(c: Context) { dir = File(c.filesDir, "faces").apply { mkdirs() }; File(dir, "template.bin").takeIf { it.exists() }?.let { load(it.readBytes(), "saved") } }

    private fun load(b: ByteArray, from: String): Boolean {
        val f = runCatching { FaceFile(b) }.getOrElse { ProtoLog.info("template ($from) unreadable: ${it.message}"); return false }
        if (f.item(17) == null) { ProtoLog.info("template ($from) has no background element"); return false }
        template = b; previews.clear()
        runCatching { f.report().lines().forEach { ProtoLog.info(it) } }
        // write runs no longer than the official encoder does (the watch's decoder may not handle longer ones)
        runCatching {
            var ml = 0; var mr = 0
            for (e in f.items) if (f.isRle(e.type)) FaceFile.rleStats(b, e.offset, e.width, e.height).let { ml = maxOf(ml, it[0]); mr = maxOf(mr, it[1]) }
            if (ml >= 2) FaceFile.maxLiteral = ml
            if (mr >= 2) FaceFile.maxRun = mr
            ProtoLog.info("encoder limits: literal<=${FaceFile.maxLiteral} run<=${FaceFile.maxRun}")
        }
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
        // (digit/hand recolouring is off until the rebuilt file is confirmed safe on this watch)
        val out = f.build()
        FaceFile.putU32(out, 0, 990_000_000 + i)
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

    /** Cypress designs are switched off: the rebuilt Midnight file made the watch restart over and over. */
    fun installDesign(i: Int) { ui.value = ui.value.copy(message = "Cypress designs are turned off for now — the last one made the watch restart. Official faces still install.") }
    fun installOfficial(o: Official) = install(o.title) { download(o.binUrl) ?: error("download failed") }

    fun nameOf(id: Long): String =
        if (id in 990_000_000L until 990_000_000L + FaceDesigns.all.size) "Cypress · " + FaceDesigns.all[(id - 990_000_000L).toInt()].name
        else ui.value.official.firstOrNull { it.id == id.toString() }?.title ?: "Downloaded face #$id"

    private var pending: Pair<String, suspend () -> ByteArray>? = null

    fun cancelReplace() { pending = null; ui.value = ui.value.copy(needRoom = null) }

    /** Remove a downloaded face (apply with operate 2) and wait for the list to change. */
    private suspend fun removeFace(f: Sync.Face): Boolean {
        Watch.send(Proto.faceApply(f.id, f.version, 2))
        delay(1500); Watch.send(Proto.faceList())
        repeat(10) { delay(300); if (Sync.faces.value.none { it.id == f.id }) return true }
        return false
    }

    fun remove(f: Sync.Face) {
        if (job?.isActive == true) return
        job = Watch.scope.launch {
            ui.value = ui.value.copy(message = "Removing ${nameOf(f.id)}…")
            val ok = removeFace(f)
            ui.value = ui.value.copy(message = if (ok) "${nameOf(f.id)} removed from the watch." else "The watch kept ${nameOf(f.id)} (it may not allow removing that one).")
        }
    }

    fun replace(f: Sync.Face) {
        val p = pending ?: return
        pending = null
        ui.value = ui.value.copy(needRoom = null)
        install(p.first, f, p.second)
    }

    private fun install(label: String, freeing: Sync.Face? = null, make: suspend () -> ByteArray) {
        if (job?.isActive == true) return
        job = Watch.scope.launch {
            try {
                if (!Watch.ready) error("Connect the watch first")
                // the watch only keeps [slots] downloaded faces; when they're all taken one has to go first
                Watch.send(Proto.faceList()); delay(1200)
                val users = Sync.faces.value.filter { it.userSlot }
                if (freeing != null) {
                    ui.value = ui.value.copy(progress = 0f, message = "Making room: removing ${nameOf(freeing.id)}…")
                    if (!removeFace(freeing)) error("the watch wouldn't remove ${nameOf(freeing.id)}")
                } else if (slots > 0 && users.size >= slots) {
                    pending = label to make
                    ui.value = ui.value.copy(progress = null, message = null, needRoom = users)
                    return@launch
                }
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

    /**
     * GloryFit "JX" face transfer (the same sequence the official app uses):
     *   apply the face id first (01 E3 AB 05 … 01) — the watch answers operate=1 when it wants the file
     *   01 E3 AB 03 01 01            start            -> 01 E3 AC 02 01 02 ready
     *   01 E4 AB 01 <idx BE16> <data> sections (file sent as-is), no gaps; the watch sends status 7 after every 4 KB
     *   01 E3 AB 04 01 01            all sent         -> 01 E3 AC 02 01 01 stored + CRC verified
     * status: 1 done, 2 ready, 3 cancelled, 4 CRC failed, 5 too big, 6 resume from <BE16, 1-based>, 7 continue, 8 full
     */
    private suspend fun uploadOnce(data: ByteArray, label: String, attempt: Int) {
        val events = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val l: (ByteArray) -> Unit = { p -> if (p.size >= 4 && (p[1].toInt() and 0xff) == 0xe3) events.trySend(p) }
        Watch.packetListeners += l
        fun u(p: ByteArray, i: Int) = p[i].toInt() and 0xff
        fun status(p: ByteArray) = if (p.size >= 6 && u(p, 2) == 0xac && u(p, 3) == 0x02) u(p, 5) else null
        fun drain() { while (events.tryReceive().isSuccess) { } }
        val id = FaceFile.u32(data, 0).toLong() and 0xffffffffL
        try {
            ProtoLog.info("face upload: $label ${data.size} bytes, id $id (try $attempt)")
            // 1. tell the watch which face this is; it answers whether it needs the file
            drain()
            Watch.send(Proto.faceApply(id, "1.0.0", 1))
            val ap = withTimeoutOrNull(8000) { var r: ByteArray; do { r = events.receive() } while (!(u(r, 2) == 0xab && u(r, 3) == 0x05)); r }
            val operate = if (ap != null && ap.size >= 15) u(ap, 14) else 1
            ProtoLog.info("apply reply: ${ap?.hex() ?: "none"} -> operate $operate")
            if (operate != 1) {
                ui.value = ui.value.copy(progress = null, message = "$label is already on your watch — switched to it.")
                Sync.current.value = id; Watch.send(Proto.faceList()); return
            }
            // 2. start
            drain()
            Watch.send(Proto.faceUploadStart())
            val start = withTimeoutOrNull(15000) { var r: ByteArray; do { r = events.receive() } while (status(r) == null); r }
            when (val code = start?.let { status(it) }) {
                null -> error("the watch didn't answer the start request")
                2 -> {}
                5 -> error("the watch says the face is too big")
                8 -> error("the watch is full. On the watch, long-press the face, delete a downloaded face, then try again")
                else -> throw Stopped(code)
            }
            // 3. sections
            val packet = (Watch.maxPacket).coerceIn(20, 512)
            val chunk = packet - 6
            val total = (data.size + chunk - 1) / chunk
            ProtoLog.info("${data.size} B in $total sections of $chunk B")
            Watch.fastLink(true)
            val t0 = System.currentTimeMillis()
            try {
                var section = 0
                var block = 0
                var continues = 0
                fun handle(r: ByteArray): Int? {   // returns a resume section, throws on refusal
                    when (val st = status(r)) {
                        7 -> continues++
                        6 -> if (r.size >= 8) return maxOf(0, ((u(r, 6) shl 8) or u(r, 7)) - 1)
                        1, 3, 4, 5, 8 -> { Watch.clearQueue(); throw Stopped(st) }
                        else -> {}
                    }
                    return null
                }
                while (section < total) {
                    val off = section * chunk
                    val len = minOf(chunk, data.size - off)
                    Watch.send(Proto.b(1, 0xe4, 0xab, 0x01, (section ushr 8) and 0xff, section and 0xff) + data.copyOfRange(off, off + len))
                    var resume: Int? = null
                    while (true) { val r = events.tryReceive().getOrNull() ?: break; handle(r)?.let { resume = it } }
                    ui.value = ui.value.copy(progress = (section + 1) / total.toFloat(), message = "Sending $label… ${(section + 1) * 100 / total}%")
                    if (resume != null) {
                        ProtoLog.info("watch asked to resume at section $resume")
                        Watch.clearQueue(); section = resume!!; block = section * chunk / 4096; continue
                    }
                    if (section + 1 >= total) break
                    val nextBlock = (section + 1) * chunk / 4096
                    if (nextBlock != block) {
                        block = nextBlock
                        // wait for the watch's "4 KB consumed" (status 7); the app carries on after 5 s
                        val want = continues + 1
                        val ok = withTimeoutOrNull(5000) {
                            while (continues < want && resume == null) handle(events.receive())?.let { resume = it }
                        }
                        if (ok == null) ProtoLog.info("no flow-control ack after section $section, continuing")
                        if (resume != null) {
                            ProtoLog.info("watch asked to resume at section $resume")
                            Watch.clearQueue(); section = resume!!; block = section * chunk / 4096; continue
                        }
                    } else if ((section + 1) % 10 == 0) delay(20)
                    section++
                }
                // let the queue empty before announcing the end
                while (Watch.queued > 0) { events.tryReceive().getOrNull()?.let { handle(it) }; delay(20) }
                ProtoLog.info("sent $total sections in ${System.currentTimeMillis() - t0} ms")
            } finally { Watch.fastLink(false) }
            // 4. end + verdict
            delay(500)
            Watch.send(Proto.b(1, 0xe3, 0xab, 0x04, 0x01, 0x01))
            ui.value = ui.value.copy(progress = 1f, message = "Watch is checking the file…")
            val fin = withTimeoutOrNull(60000) { var r: ByteArray; do { r = events.receive() } while (status(r) !in listOf(1, 3, 4, 5, 8)); status(r) }
            ProtoLog.info("transfer verdict: ${fin ?: "no answer"}")
            when (fin) {
                1 -> {}
                null -> error("the watch never confirmed the file")
                else -> throw Stopped(fin)
            }
            delay(800)
            Watch.send(Proto.faceApply(id, "1.0.0", 1))
            delay(1200)
            Watch.send(Proto.faceList())
            delay(1500)
            val there = Sync.faces.value.any { it.id == id }
            ProtoLog.info("after install: face $id ${if (there) "is" else "is NOT"} in the watch's list")
            ui.value = ui.value.copy(progress = null, message = if (there) "$label is on your watch." else "The watch took the file but didn't list $label. Send me the log.")
        } finally {
            Watch.packetListeners -= l
        }
    }
}
