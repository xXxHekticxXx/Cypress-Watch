package com.cypress.watch.ble

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * The GloryFit Pro ("Nadal") watch language used by the W60 / NAL-WB00.
 *
 * Every packet is  01 | CMD | MODE | FIELD | LEN | VALUE...   MODE: aa = get, ab = set, ac = watch report.
 * Longer messages are sent as  01 CMD ab ab <index> <data...>  packets and closed with
 * 01 CMD ab ab fd <xor of every byte sent>.  Watch replies come back the same way.
 * Field layouts follow what Gadgetbridge (GloryFitPro / DM58, same chip family) and the open HT36 project
 * verified on real watches.
 */
object Proto {
    const val H: Byte = 0x01
    const val GET: Byte = 0xaa.toByte()
    const val SET: Byte = 0xab.toByte()
    const val REPORT: Byte = 0xac.toByte()
    const val END: Byte = 0xfd.toByte()

    const val TIME: Byte = 0xa3.toByte()
    const val INFO: Byte = 0xa4.toByte()
    const val CONTROL: Byte = 0xa5.toByte()
    const val NOTIFY: Byte = 0xb0.toByte()
    const val NOTIFY_CFG: Byte = 0xb2.toByte()
    const val DAY: Byte = 0xc3.toByte()
    const val HEALTH_A: Byte = 0xc5.toByte()
    const val HISTORY: Byte = 0xc6.toByte()
    const val ALARM: Byte = 0xc7.toByte()
    const val WEATHER: Byte = 0xe0.toByte()
    const val MUSIC: Byte = 0xe2.toByte()
    const val FACE: Byte = 0xe3.toByte()

    fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    fun be32(v: Long) = b(((v ushr 24) and 0xff).toInt(), ((v ushr 16) and 0xff).toInt(), ((v ushr 8) and 0xff).toInt(), (v and 0xff).toInt())
    fun be16(v: Int) = b((v ushr 8) and 0xff, v and 0xff)
    private fun utf16(s: String, maxChars: Int) = s.replace("\r", "").take(maxChars).toByteArray(Charsets.UTF_16BE)

    fun xor(chunks: List<ByteArray>): Int { var x = 0; for (c in chunks) for (v in c) x = x xor (v.toInt() and 0xff); return x }

    /** Split a body into "01 CMD ab ab <idx> ..." packets plus the closing "fd <xor>" packet. */
    fun chunked(cmd: Byte, body: ByteArray, maxPacket: Int): List<ByteArray> {
        val slice = (maxPacket - 5).coerceAtLeast(10)
        val out = mutableListOf<ByteArray>()
        var off = 0; var idx = 0
        do {
            val end = minOf(off + slice, body.size)
            out += b(1, cmd.toInt() and 0xff, 0xab, 0xab, idx and 0xff) + body.copyOfRange(off, end)
            off = end; idx++
        } while (off < body.size)
        out += b(1, cmd.toInt() and 0xff, 0xab, 0xab, 0xfd, xor(out))
        return out
    }

    // ---------- simple commands ----------
    fun getAll(cmd: Byte) = byteArrayOf(H, cmd, GET, GET)
    fun battery() = b(1, 0xa4, 0xaa, 0x16)
    fun findWatch(on: Boolean) = b(1, 0xa5, 0xab, 0x04, 0x01, if (on) 1 else 0)
    fun enableFindPhone() = b(1, 0xa5, 0xab, 0x0a, 0x01, 0x01)

    /** UTC epoch + time zone as [0x80|hours][minutes] (east of UTC) or [hours][minutes] (west). */
    fun setTime(now: Long = System.currentTimeMillis()): ByteArray {
        val offMin = TimeZone.getDefault().getOffset(now) / 60000
        val h = Math.abs(offMin) / 60; val m = Math.abs(offMin) % 60
        return b(1, 0xa3, 0x01, 0x04) + be32(now / 1000) + b(0x10, 0x02, (if (offMin >= 0) 0x80 else 0) or h, m)
    }

    // ---------- notifications ----------
    fun enableNotifications(mtu: Int) = chunked(NOTIFY_CFG, b(0xab, 0x01, 0x01, 0x01, 0xab, 0x02, 0x01, 0x01), mtu)

    /** type: 4 = generic app icon (verified); the watch picks the icon from it. */
    fun notification(title: String, body: String, app: String, type: Int, mtu: Int): List<ByteArray> {
        val o = ByteArrayOutputStream()
        fun tlv(f: Int, v: ByteArray) { o.write(0xab); o.write(f); o.write(v.size); o.write(v) }
        if (app.isNotBlank()) tlv(0x01, utf16(app, 20))
        tlv(0x03, utf16(body.ifBlank { " " }, 110))
        tlv(0x07, utf16(title.ifBlank { app }, 30))
        tlv(0x08, b(type))
        tlv(0x09, b(1))
        return chunked(NOTIFY, o.toByteArray(), mtu)
    }

    // ---------- music ----------
    fun musicInfo(artist: String, title: String, playing: Boolean, volMax: Int, vol: Int, mtu: Int): List<ByteArray> {
        val o = ByteArrayOutputStream()
        fun tlv(f: Int, v: ByteArray) { o.write(0xab); o.write(f); o.write(v.size); o.write(v) }
        tlv(0x01, utf16(artist, 32)); tlv(0x02, utf16(title, 48))
        tlv(0x03, b(if (playing) 3 else 2)); tlv(0x04, b(volMax.coerceIn(1, 63))); tlv(0x05, b(vol.coerceIn(0, 63)))
        return chunked(MUSIC, o.toByteArray(), mtu)
    }

    // ---------- alarms (slots 1..n) ----------
    fun alarms(list: List<com.cypress.watch.Alarm>, mtu: Int): List<ByteArray> {
        val o = ByteArrayOutputStream()
        list.take(10).forEachIndexed { i, a ->
            val idx = i + 1
            o.write(b(0xab, 0x01, 0x02, idx, idx))
            o.write(b(0xab, 0x02, 0x02, idx, a.days and 0x7f))
            o.write(b(0xab, 0x03, 0x02, idx, if (a.on) 1 else 0))
            o.write(b(0xab, 0x04, 0x03, idx, a.hour, a.minute))
            o.write(b(0xab, 0x05, 0x01, idx))
        }
        return chunked(ALARM, o.toByteArray(), mtu)
    }

    // ---------- weather (temperatures in °C; the watch converts to its own unit) ----------
    data class Day(val high: Int, val low: Int, val code: Int, val humidity: Int, val sunrise: Long, val sunset: Long, val dayStart: Long)
    data class Hour(val ts: Long, val temp: Int, val code: Int)

    fun weatherNow(city: String, cur: Int, high: Int, low: Int, humidity: Int, uv: Int, code: Int, ts: Long, mtu: Int): List<ByteArray> {
        val o = ByteArrayOutputStream()
        val c = utf16(city, 24)
        o.write(b(0xab, 0x05, c.size)); o.write(c)
        o.write(b(0xab, 0x06, 0x02)); o.write(be16(cur))
        o.write(b(0xab, 0x07, 0x02)); o.write(be16(high))
        o.write(b(0xab, 0x08, 0x02)); o.write(be16(low))
        o.write(b(0xab, 0x0a, 0x01, uv.coerceIn(0, 15)))
        o.write(b(0xab, 0x0b, 0x04)); o.write(be32(ts))
        o.write(b(0xab, 0x0f, 0x02, humidity.coerceIn(0, 100), code))
        return chunked(WEATHER, o.toByteArray(), mtu)
    }

    fun weatherForecast(ts: Long, hours: List<Hour>, days: List<Day>, mtu: Int): List<ByteArray> {
        val o = ByteArrayOutputStream()
        o.write(b(0xab, 0x0b, 0x04)); o.write(be32(ts))
        for (h in hours.take(24)) {
            val e = ByteArrayOutputStream()
            e.write(b(0x01, 0x04)); e.write(be32(h.ts))
            e.write(b(0x02, 0x01, h.code))
            e.write(b(0x03, 0x02)); e.write(be16(h.temp))
            val eb = e.toByteArray(); o.write(b(0xab, 0x0d, eb.size)); o.write(eb)
        }
        for (d in days.take(7)) {
            val e = ByteArrayOutputStream()
            e.write(b(0x01, 0x02)); e.write(be16(d.high))
            e.write(b(0x02, 0x02)); e.write(be16(d.low))
            e.write(b(0x03, 0x01, 0))
            e.write(b(0x04, 0x04)); e.write(be32(d.dayStart + 8 * 3600))
            e.write(b(0x05, 0x04)); e.write(be32(d.dayStart + 23 * 3600))
            e.write(b(0x06, 0x01, d.code))
            e.write(b(0x07, 0x04)); e.write(be32(d.sunrise))
            e.write(b(0x08, 0x04)); e.write(be32(d.sunset))
            e.write(b(0x09, 0x04)); e.write(be32(d.dayStart))
            e.write(b(0x0a, 0x01, d.humidity.coerceIn(0, 100)))
            e.write(b(0x0b, 0x01, d.code))
            val eb = e.toByteArray(); o.write(b(0xab, 0x0e, eb.size)); o.write(eb)
        }
        return chunked(WEATHER, o.toByteArray(), mtu)
    }

    /** WMO weather code (Open-Meteo) -> watch icon: 0 clear, 1 partly cloudy, 2 cloudy, 3 light rain, 4 storm,
     *  6 hail/ice, 7 rain, 8 heavy rain, 10 showers, 11 heavy showers, 14 light snow, 15 snow, 16 heavy snow, 18 fog. */
    fun wmoToWatch(w: Int): Int = when (w) {
        0 -> 0; 1, 2 -> 1; 3 -> 2; 45, 48 -> 18
        51, 53, 55, 61 -> 3; 56, 57, 66, 67 -> 6; 63 -> 7; 65 -> 8
        71 -> 14; 73, 77 -> 15; 75 -> 16; 80 -> 10; 81 -> 10; 82 -> 11; 85, 86 -> 15
        95 -> 4; 96, 99 -> 4
        else -> 2
    }

    // ---------- health history (same SDK commands the HT36 project verified) ----------
    fun historyFitness(from: Long, to: Long) = b(1, 0xc6, 0xaa, 0x01, 0x0a) + be32(from) + be32(to) + b(0, 0)
    fun historyPrep(from: Long, to: Long) = b(1, 0xc5, 0xaa, 0x01, 0x08) + be32(from) + be32(to)
    fun historySleep(from: Long, to: Long) = b(1, 0xc6, 0xaa, 0x04, 0x08) + be32(from) + be32(to)

    // ---------- watch faces (group E3) ----------
    fun faceInfo() = b(1, 0xe3, 0xaa, 0xaa)
    fun faceList() = b(1, 0xe3, 0xaa, 0x0b)
    fun faceUploadStart() = b(1, 0xe3, 0xab, 0x03, 0x01, 0x01)
    /** Switch to / activate a face. operate 1 = after an upload, 0 = switch to an installed one. */
    fun faceApply(id: Long, version: String, operate: Int): ByteArray {
        val v = version.toByteArray(Charsets.US_ASCII)
        return b(1, 0xe3, 0xab, 0x05, 4 + v.size + 1) + be32(id) + v + b(operate)
    }
    fun faceKeep(ids: List<Long>, mtu: Int): List<ByteArray> {
        val o = ByteArrayOutputStream()
        ids.forEach { o.write(be32(it)) }
        val body = o.toByteArray()
        val first = b(1, 0xe3, 0xab, 0x01, 0x00, body.size) + body
        return listOf(first, b(1, 0xe3, 0xab, 0x01, 0xfd, xor(listOf(first))))
    }

    // ---------- reply parsing ----------
    /** Repeating "<mode> <field> <len> <value>" entries. */
    fun tlv(p: ByteArray, start: Int = 0): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var i = start
        while (i + 3 <= p.size) {
            val f = p[i + 1].toInt() and 0xff
            val len = p[i + 2].toInt() and 0xff
            if (i + 3 + len > p.size) break
            out[f] = p.copyOfRange(i + 3, i + 3 + len)
            i += 3 + len
        }
        return out
    }

    /** Plain "<field> <len> <value>" entries (inside a value). */
    fun innerTlv(p: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i + 2 <= p.size) {
            val f = p[i].toInt() and 0xff; val len = p[i + 1].toInt() and 0xff
            if (i + 2 + len > p.size) break
            out[f] = p.copyOfRange(i + 2, i + 2 + len); i += 2 + len
        }
        return out
    }

    fun num(v: ByteArray?): Int? { if (v == null || v.isEmpty()) return null; var x = 0; for (b in v) x = (x shl 8) or (b.toInt() and 0xff); return x }
    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    fun u32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    fun midnight(daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis / 1000
}
