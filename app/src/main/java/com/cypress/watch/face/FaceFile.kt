package com.cypress.watch.face

import net.jpountz.lz4.LZ4Factory
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * GloryFit / GloryFit Pro watch face (.bin).
 *
 * 24-byte header: id(4) size(4) crc32(4) width(2) height(2) 00 01 ff <version> <blocks> ff ff ff
 * then 24-byte element records (type, width, data offset, height, x, y, speed, frames, transparency, hand info)
 * ending with 00 00, then the image data.
 *  version 1: raw 16-bit colour (RGB565, big-endian)
 *  version 2: same, but most elements use a per-row run-length code
 *  version 3/4: every frame = 20-byte header (own offset, w, h, flag, method, 0, size, ...) + an LZ4 block;
 *               method 2 = RGB565 little-endian, method 3 = ARGB6666 (left untouched here)
 * Formats from the community ClockFaceEdit / wfeditor / K72 research.
 */
class FaceFile(val src: ByteArray) {
    data class Item(
        val type: Int, val width: Int, var offset: Int, val height: Int, val x: Int, val y: Int,
        val frames: Int, val copyImage: Boolean, val tableOffset: Int,
    )

    val id = src.copyOfRange(0, 4)
    val width = u16(src, 12)
    val height = u16(src, 14)
    val version: Int = (src[19].toInt() and 0xff).let { if (it == 0xff) 1 else it }
    val items = mutableListOf<Item>()
    private val dataStart: Int

    init {
        require(src.size > 48) { "file too small" }
        var o = 24
        while (o + 2 <= src.size) {
            val t = u16(src, o)
            if (t == 0) break
            require(o + 24 <= src.size) { "element table cut off" }
            items += Item(t, u16(src, o + 2), u32(src, o + 4), u16(src, o + 8), u16(src, o + 10), u16(src, o + 12),
                src[o + 16].toInt() and 0xff, src[o + 17].toInt() != 0, o)
            o += 24
            require(items.size < 200) { "too many elements" }
        }
        dataStart = o + 2
        require(version in 1..4) { "unknown face format $version" }
    }

    val rleTypes: (Int) -> Boolean = { t -> (t in 4..42) || t == 47 || t == 48 || t == 71 }
    fun isRle(t: Int) = version == 2 && rleTypes(t)

    // ---------- blocks: the data range owned by each distinct start offset ----------
    private fun blockRanges(): List<IntRange> {
        val starts = items.map { it.offset }.distinct().sorted()
        return starts.mapIndexed { i, s -> s until (if (i + 1 < starts.size) starts[i + 1] else src.size) }
    }

    /** New pixel data (ARGB) for some frames, keyed by element type -> frame index. */
    private val replaced = HashMap<Int, HashMap<Int, IntArray>>()
    private var tint: Int? = null

    fun replaceFrame(type: Int, frame: Int, argb: IntArray) { replaced.getOrPut(type) { HashMap() }[frame] = argb }
    /** Recolour every 16-bit image (keeps light/dark, swaps the hue) except the background and preview. */
    fun tintAll(color: Int) { tint = color }

    fun item(type: Int) = items.firstOrNull { it.type == type }

    // ---------- decoding (for previews) ----------
    /** ARGB frames of an element, or null when its colour format isn't one we read. */
    fun decode(it: Item): List<IntArray>? = runCatching {
        val n = it.width * it.height
        when (version) {
            1, 2 -> if (isRle(it.type)) {
                var p = it.offset
                List(it.frames) {
                    val blk = u32(src, p) + 4
                    val px = rleDecode(src, p, it.width, it.height)
                    p += blk; px
                }
            } else List(it.frames) { f -> IntArray(n) { i -> be565(src, it.offset + (f * n + i) * 2, it.copyImage) } }
            else -> {
                var p = it.offset
                List(it.frames) {
                    val w = u16(src, p + 4); val h = u16(src, p + 6); val method = src[p + 9].toInt() and 0xff
                    val size = u32(src, p + 12)
                    val raw = lz4().safeDecompressor().decompress(src, p + 20, size, w * h * (if (method == 3) 3 else 2))
                    p += 20 + size
                    if (method == 2) IntArray(w * h) { i -> le565(raw, i * 2, it.copyImage) }
                    else IntArray(w * h) { i -> argb6666(raw, i * 3) }
                }
            }
        }
    }.getOrNull()

    // ---------- encoding ----------
    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(src, 0, dataStart) // header + table; offsets patched below
        val newStart = HashMap<Int, Int>()
        for (r in blockRanges()) {
            val owner = items.first { it.offset == r.first }
            val at = out.size()
            newStart[r.first] = at
            out.write(encodeBlock(owner, r, at))
        }
        val b = out.toByteArray()
        for (it in items) putU32(b, it.tableOffset + 4, newStart[it.offset] ?: it.offset)
        putU32(b, 4, b.size - 24)
        val crc = CRC32(); crc.update(b, 24, b.size - 24)
        putU32(b, 8, crc.value.toInt())
        return b
    }

    private fun wantsChange(it: Item, f: Int) =
        replaced[it.type]?.containsKey(f) == true || (tint != null && it.type != 17 && it.type != 10)

    private fun encodeBlock(it: Item, r: IntRange, newAt: Int): ByteArray {
        val anyChange = (0 until it.frames).any { f -> wantsChange(it, f) }
        val frames = if (anyChange) decode(it) else null
        fun px(f: Int): IntArray = replaced[it.type]?.get(f) ?: frames!![f].let { p -> if (tint != null && it.type != 17 && it.type != 10) tinted(p, tint!!) else p }
        val n = it.width * it.height
        if (version <= 2) {
            if (frames == null) return src.copyOfRange(r.first, r.last + 1)
            val o = ByteArrayOutputStream()
            if (isRle(it.type)) {
                for (f in 0 until it.frames) o.write(rleEncodeFrame(px(f), it.width, it.height))
            } else {
                for (f in 0 until it.frames) { val p = px(f); for (i in 0 until n) { val v = to565(p[i]); o.write(v ushr 8); o.write(v and 0xff) } }
            }
            // keep anything after the frames that belonged to this block (rare padding)
            val used = blockLengthV2(it)
            if (r.first + used <= r.last) o.write(src, r.first + used, r.last + 1 - (r.first + used))
            return o.toByteArray()
        }
        // v3/v4: frame by frame; ARGB6666 frames and untouched frames are copied as they are
        val o = ByteArrayOutputStream()
        var p = it.offset
        for (f in 0 until it.frames) {
            if (p + 20 > src.size) break
            val size = u32(src, p + 12); val method = src[p + 9].toInt() and 0xff
            val meta = src.copyOfRange(p, p + 20)
            // the first 4 bytes point at the frame itself; move them with it
            val newFrameAt = newAt + o.size()
            putU32(meta, 0, u32(src, p) - p + newFrameAt)
            if (frames != null && method == 2 && wantsChange(it, f)) {
                val pix = px(f)
                val raw = ByteArray(pix.size * 2)
                for (i in pix.indices) { val v = to565(pix[i]); raw[i * 2] = v.toByte(); raw[i * 2 + 1] = (v ushr 8).toByte() }
                val comp = lz4().fastCompressor().compress(raw)
                putU32(meta, 12, comp.size)
                o.write(meta); o.write(comp)
            } else { o.write(meta); o.write(src, p + 20, size) }
            p += 20 + size
        }
        if (p <= r.last) o.write(src, p, r.last + 1 - p)
        return o.toByteArray()
    }

    private fun blockLengthV2(it: Item): Int {
        if (!isRle(it.type)) return it.width * it.height * it.frames * 2
        var p = it.offset
        repeat(it.frames) { p += u32(src, p) + 4 }
        return p - it.offset
    }

    // ---------- pixels ----------
    private fun tinted(px: IntArray, color: Int): IntArray {
        val cr = (color ushr 16) and 0xff; val cg = (color ushr 8) and 0xff; val cb = color and 0xff
        return IntArray(px.size) { i ->
            val p = px[i]
            val a = p ushr 24
            if (a == 0) p else {
                val r = (p ushr 16) and 0xff; val g = (p ushr 8) and 0xff; val b = p and 0xff
                val l = maxOf(r, g, b) / 255f
                if (l < 0.06f) p else (a shl 24) or ((cr * l).toInt() shl 16) or ((cg * l).toInt() shl 8) or (cb * l).toInt()
            }
        }
    }

    companion object {
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        fun putU32(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte() }
        fun lz4(): LZ4Factory = LZ4Factory.fastestJavaInstance()

        fun to565(argb: Int): Int {
            val r = (argb ushr 16) and 0xff; val g = (argb ushr 8) and 0xff; val b = argb and 0xff
            return ((r ushr 3) shl 11) or ((g ushr 2) shl 5) or (b ushr 3)
        }
        fun from565(v: Int, opaque: Boolean): Int {
            val r = ((v ushr 11) and 0x1f) * 255 / 31; val g = ((v ushr 5) and 0x3f) * 255 / 63; val b = (v and 0x1f) * 255 / 31
            val a = if (opaque || v != 0) 0xff else 0
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        fun be565(b: ByteArray, o: Int, copy: Boolean) = from565(((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff), copy)
        fun le565(b: ByteArray, o: Int, copy: Boolean) = from565((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8), copy)
        fun argb6666(b: ByteArray, o: Int): Int {
            val v = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16)
            val a = ((v ushr 18) and 0x3f) * 255 / 63; val r = ((v ushr 12) and 0x3f) * 255 / 63
            val g = ((v ushr 6) and 0x3f) * 255 / 63; val bl = (v and 0x3f) * 255 / 63
            return (a shl 24) or (r shl 16) or (g shl 8) or bl
        }

        /** Per-row run-length image: u32 size, h × u32 row offsets, rows of <count><repeat><colours BE565...>. */
        fun rleDecode(b: ByteArray, start: Int, w: Int, h: Int): IntArray {
            val px = IntArray(w * h)
            val table = start + 4
            for (y in 0 until h) {
                var c = table + u32(b, table + y * 4)
                var i = y * w; val end = i + w
                while (i < end) {
                    val count = b[c].toInt() and 0xff; val rep = b[c + 1].toInt() and 0xff; c += 2
                    if (count == 0 || rep == 0) break
                    for (k in 0 until count) {
                        val col = be565(b, c, false); c += 2
                        for (r in 0 until rep) { if (i >= end) break; px[i++] = col }
                    }
                }
            }
            return px
        }

        fun rleEncodeFrame(px: IntArray, w: Int, h: Int): ByteArray {
            val rows = (0 until h).map { y -> rleRow(IntArray(w) { to565(px[y * w + it]).let { v -> if (px[y * w + it] ushr 24 == 0) 0 else v } }) }
            val table = h * 4
            val body = rows.sumOf { it.size }
            val out = ByteArray(4 + table + body)
            putU32(out, 0, table + body)
            var off = table
            rows.forEachIndexed { y, r -> putU32(out, 4 + y * 4, off); System.arraycopy(r, 0, out, 4 + off, r.size); off += r.size }
            return out
        }

        private fun rleRow(p: IntArray): ByteArray {
            val o = ByteArrayOutputStream()
            var i = 0
            while (i < p.size) {
                var e = i + 1
                while (e < p.size && p[e] == p[i] && e - i < 255) e++
                if (e - i >= 2) { o.write(1); o.write(e - i); o.write(p[i] ushr 8); o.write(p[i] and 0xff); i = e; continue }
                val s = i; i++
                while (i < p.size && i - s < 255) { if (i + 1 < p.size && p[i + 1] == p[i]) break; i++ }
                o.write(i - s); o.write(1)
                for (k in s until i) { o.write(p[k] ushr 8); o.write(p[k] and 0xff) }
            }
            return o.toByteArray()
        }
    }
}
