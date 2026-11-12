package com.cypress.watch.ble

/**
 * Decoders for the watch's history replies.
 * Fitness ("motion frames", 01 c6 aa 01 ...): a 2-byte index and a start time, then per-minute slots
 * (offset, a bit mask saying which values follow, then the values). Layout from the GloryFitPro SDK as
 * worked out and checked on hardware by the open HT36 project.
 * Sleep (01 c6 aa 04 ...): 3-byte header then 7-byte records <start BE4> <minutes BE2> <stage>.
 */
object History {
    data class Slot(
        val ts: Long, val steps: Int? = null, val calories: Int? = null, val distance: Int? = null,
        val hr: Int? = null, val hrMax: Int? = null, val hrMin: Int? = null, val restingHr: Int? = null, val spo2: Int? = null,
    )
    data class SleepPart(val start: Long, val minutes: Int, val stage: Int)

    private val ONE_BYTE = setOf(1, 2, 4, 5, 6, 7, 9, 10, 12, 13)

    private fun mask(b: ByteArray, p: Int): Triple<Long, Int, Boolean> {
        fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xff else 0
        val b0 = u(p); if (b0 < 0x80) return Triple(b0.toLong(), 1, false)
        val b1 = u(p + 1); if (b1 < 0x80) return Triple((b0 or (b1 shl 8)).toLong(), 2, false)
        val b2 = u(p + 2); if (b2 < 0x80) return Triple((b0 or (b1 shl 8) or (b2 shl 16)).toLong(), 3, false)
        val b3 = u(p + 3)
        return Triple((b0.toLong() or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24)), 4, b3 >= 0x80)
    }

    private fun sane(t: Long) = t in 1_600_000_000L..2_100_000_000L

    /** Joins the data packets of one fitness reply and decodes the slots. */
    fun fitness(packets: List<ByteArray>): List<Slot> {
        val buf = java.io.ByteArrayOutputStream()
        for (f in packets) if (f.size > 5 && (f[4].toInt() and 0xff) != 0xfd) buf.write(f, 5, f.size - 5)
        var b = buf.toByteArray()
        if (b.size < 8) return emptyList()
        if (!sane(Proto.u32(b, 2)) && sane(Proto.u32(b, 3))) b = b.copyOfRange(1, b.size)
        return decodeFrames(b)
    }

    /** One or more back-to-back frames: <index BE2> <start BE4> slots... A new frame starts where a sane start time appears. */
    private fun decodeFrames(b: ByteArray): List<Slot> {
        val out = mutableListOf<Slot>()
        var i = 0
        while (i + 6 <= b.size) {
            val start = Proto.u32(b, i + 2)
            if (!sane(start)) break
            i = decodeFrame(b, i + 6, start, out)
        }
        return out
    }

    private fun decodeFrame(b: ByteArray, from: Int, start: Long, out: MutableList<Slot>): Int {
        var i = from
        val now = System.currentTimeMillis() / 1000 + 86400
        while (i + 3 <= b.size) {
            val off = Proto.u16(b, i)
            // a following frame header looks like an offset far outside one day; stop and let the caller restart
            if (off > 60 * 24 * 8 && i + 6 <= b.size && sane(Proto.u32(b, i + 2))) return i
            val (m, len, hi) = mask(b, i + 2)
            var vp = i + 2 + len
            if (hi) vp += mask(b, vp).second
            var steps: Int? = null; var cal: Int? = null; var dist: Int? = null; var hr: Int? = null
            var hrMax: Int? = null; var hrMin: Int? = null; var rest: Int? = null; var spo2: Int? = null
            for (bit in 1..32) {
                if (bit % 8 == 0) continue
                if (m and (1L shl (bit - 1)) == 0L) continue
                if (vp >= b.size) break
                when {
                    bit == 17 -> vp += 2
                    bit == 27 -> vp += 2
                    bit in ONE_BYTE -> {
                        val v = b[vp].toInt() and 0xff
                        when (bit) { 1 -> steps = v; 2 -> cal = v; 4, 10, 13 -> rest = v; 5 -> hrMax = v; 6 -> hrMin = v; 7, 12 -> hr = v; 9 -> spo2 = v }
                        vp += 1
                    }
                    else -> {
                        if (vp + 1 < b.size) { val v = Proto.u16(b, vp); if (bit == 3) dist = v }
                        vp += 2
                    }
                }
            }
            val ts = start + off * 60L
            if (ts in 1_600_000_000L..now) out += Slot(ts, steps, cal, dist, hr, hrMax, hrMin, rest, spo2)
            if (vp <= i) break
            i = vp
        }
        return b.size
    }

    fun sleep(packets: List<ByteArray>): List<SleepPart> {
        val data = packets.filter { it.size >= 6 && (it[4].toInt() and 0xff) != 0xfd }
        val buf = java.io.ByteArrayOutputStream()
        for (f in data) buf.write(f, 5, f.size - 5)
        val b = buf.toByteArray()
        val now = System.currentTimeMillis() / 1000
        val out = mutableListOf<SleepPart>()
        var i = 3
        while (i + 7 <= b.size) {
            val st = Proto.u32(b, i); val min = Proto.u16(b, i + 4); val type = b[i + 6].toInt() and 0xff
            if (st in 1_600_000_000L..(now + 86400) && min in 1..1440) out += SleepPart(st, min, type)
            i += 7
        }
        return out
    }

    /** Sleep stage names (UTE convention). */
    fun stageName(t: Int) = when (t) { 1 -> "Deep"; 2 -> "Light"; 3 -> "REM"; 4 -> "Awake"; else -> "Other" }
}
