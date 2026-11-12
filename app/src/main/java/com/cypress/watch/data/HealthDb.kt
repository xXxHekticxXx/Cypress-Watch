package com.cypress.watch.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cypress.watch.ble.History
import java.io.File

/** Health history kept on the phone only. */
class HealthDb(c: Context) : SQLiteOpenHelper(c, "health.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE minute(ts INTEGER PRIMARY KEY, steps INTEGER, cal INTEGER, dist INTEGER, hr INTEGER, hrmax INTEGER, hrmin INTEGER, rest INTEGER, spo2 INTEGER)")
        db.execSQL("CREATE TABLE sleep(start INTEGER PRIMARY KEY, minutes INTEGER, stage INTEGER)")
        db.execSQL("CREATE TABLE day(day INTEGER PRIMARY KEY, steps INTEGER, cal INTEGER, dist INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun putSlots(list: List<History.Slot>) = writableDatabase.run {
        beginTransaction()
        try {
            for (s in list) {
                // keep any value already stored for this minute when the new record lacks it
                val old = rawQuery("SELECT steps,cal,dist,hr,hrmax,hrmin,rest,spo2 FROM minute WHERE ts=?", arrayOf(s.ts.toString())).use { c ->
                    if (c.moveToFirst()) IntArray(8) { if (c.isNull(it)) -1 else c.getInt(it) } else null
                }
                fun pick(v: Int?, i: Int): Int? = v ?: old?.get(i)?.takeIf { it >= 0 }
                val cv = ContentValues().apply {
                    put("ts", s.ts); put("steps", pick(s.steps, 0)); put("cal", pick(s.calories, 1)); put("dist", pick(s.distance, 2))
                    put("hr", pick(s.hr, 3)); put("hrmax", pick(s.hrMax, 4)); put("hrmin", pick(s.hrMin, 5)); put("rest", pick(s.restingHr, 6)); put("spo2", pick(s.spo2, 7))
                }
                insertWithOnConflict("minute", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            setTransactionSuccessful()
        } finally { endTransaction() }
    }

    fun putSleep(list: List<History.SleepPart>) = writableDatabase.run {
        for (s in list) insertWithOnConflict("sleep", null, ContentValues().apply { put("start", s.start); put("minutes", s.minutes); put("stage", s.stage) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The watch's own running total for a day (more reliable than adding up minutes). */
    fun putDay(dayStart: Long, steps: Int?, cal: Int?, dist: Int?) {
        writableDatabase.insertWithOnConflict("day", null, ContentValues().apply { put("day", dayStart); put("steps", steps); put("cal", cal); put("dist", dist) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class DaySum(val day: Long, val steps: Int, val cal: Int, val distM: Int)
    fun daySum(dayStart: Long): DaySum {
        val end = dayStart + 86400
        val m = readableDatabase.rawQuery("SELECT IFNULL(SUM(steps),0), IFNULL(SUM(cal),0), IFNULL(SUM(dist),0) FROM minute WHERE ts>=? AND ts<?", arrayOf(dayStart.toString(), end.toString())).use { c ->
            c.moveToFirst(); Triple(c.getInt(0), c.getInt(1), c.getInt(2))
        }
        val d = readableDatabase.rawQuery("SELECT steps,cal,dist FROM day WHERE day=?", arrayOf(dayStart.toString())).use { c ->
            if (c.moveToFirst()) Triple(if (c.isNull(0)) -1 else c.getInt(0), if (c.isNull(1)) -1 else c.getInt(1), if (c.isNull(2)) -1 else c.getInt(2)) else null
        }
        return DaySum(dayStart, maxOf(m.first, d?.first ?: -1), maxOf(m.second, d?.second ?: -1), maxOf(m.third, d?.third ?: -1))
    }

    data class Point(val ts: Long, val v: Int)
    fun series(col: String, from: Long, to: Long): List<Point> =
        readableDatabase.rawQuery("SELECT ts,$col FROM minute WHERE ts>=? AND ts<? AND $col IS NOT NULL AND $col>0 ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            val out = mutableListOf<Point>(); while (c.moveToNext()) out += Point(c.getLong(0), c.getInt(1)); out
        }

    fun sleepParts(from: Long, to: Long): List<History.SleepPart> =
        readableDatabase.rawQuery("SELECT start,minutes,stage FROM sleep WHERE start>=? AND start<? ORDER BY start", arrayOf(from.toString(), to.toString())).use { c ->
            val out = mutableListOf<History.SleepPart>(); while (c.moveToNext()) out += History.SleepPart(c.getLong(0), c.getInt(1), c.getInt(2)); out
        }

    /** Everything as one spreadsheet-friendly CSV. */
    fun exportCsv(f: File): File {
        f.bufferedWriter().use { w ->
            w.write("time,steps,calories,distance_m,heart_rate,heart_rate_max,heart_rate_min,resting_hr,spo2\n")
            readableDatabase.rawQuery("SELECT ts,steps,cal,dist,hr,hrmax,hrmin,rest,spo2 FROM minute ORDER BY ts", null).use { c ->
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                while (c.moveToNext()) {
                    w.write(fmt.format(java.util.Date(c.getLong(0) * 1000)))
                    for (i in 1..8) { w.write(","); if (!c.isNull(i)) w.write(c.getInt(i).toString()) }
                    w.write("\n")
                }
            }
            w.write("\nsleep_start,minutes,stage\n")
            readableDatabase.rawQuery("SELECT start,minutes,stage FROM sleep ORDER BY start", null).use { c ->
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                while (c.moveToNext()) w.write("${fmt.format(java.util.Date(c.getLong(0) * 1000))},${c.getInt(1)},${History.stageName(c.getInt(2))}\n")
            }
        }
        return f
    }
}
