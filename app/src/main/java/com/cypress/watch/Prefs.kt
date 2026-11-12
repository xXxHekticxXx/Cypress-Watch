package com.cypress.watch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers between launches. */
object Prefs {
    private lateinit var p: SharedPreferences
    fun init(c: Context) { p = c.getSharedPreferences("cypress_watch", Context.MODE_PRIVATE) }

    var address: String?
        get() = p.getString("address", null)
        set(v) = p.edit().putString("address", v).apply()
    var deviceName: String?
        get() = p.getString("deviceName", null)
        set(v) = p.edit().putString("deviceName", v).apply()

    /** Rescue mode: on every connection, first switch the watch to a built-in face and remove any Cypress face. */
    var rescue: Boolean
        get() = p.getBoolean("rescue_v1", true)
        set(v) = p.edit().putBoolean("rescue_v1", v).apply()

    var forwardCalls: Boolean
        get() = p.getBoolean("calls", true)
        set(v) = p.edit().putBoolean("calls", v).apply()
    var forwardTexts: Boolean
        get() = p.getBoolean("texts", true)
        set(v) = p.edit().putBoolean("texts", v).apply()
    var quietHours: Boolean
        get() = p.getBoolean("quiet", false)
        set(v) = p.edit().putBoolean("quiet", v).apply()
    var quietFrom: Int // minutes after midnight
        get() = p.getInt("quietFrom", 22 * 60)
        set(v) = p.edit().putInt("quietFrom", v).apply()
    var quietTo: Int
        get() = p.getInt("quietTo", 6 * 60)
        set(v) = p.edit().putInt("quietTo", v).apply()

    /** package -> forward? Apps not in the map use [defaultForApp]. */
    fun appEnabled(pkg: String): Boolean = p.getBoolean("app:$pkg", defaultForApp(pkg))
    fun setAppEnabled(pkg: String, on: Boolean) = p.edit().putBoolean("app:$pkg", on).apply()
    private fun defaultForApp(pkg: String) = DEFAULT_ON.any { pkg.startsWith(it) }
    private val DEFAULT_ON = listOf(
        "com.facebook", "com.google.android.gm", "com.google.android.calendar", "com.samsung.android.calendar",
        "com.whatsapp", "com.instagram", "com.snapchat", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "org.telegram", "com.discord", "com.microsoft.teams", "com.Slack",
    )

    var syncTime: Boolean
        get() = p.getBoolean("syncTime", true)
        set(v) = p.edit().putBoolean("syncTime", v).apply()
    var weather: Boolean
        get() = p.getBoolean("weather", true)
        set(v) = p.edit().putBoolean("weather", v).apply()
    var weatherCity: String
        get() = p.getString("wCity", "Sorrento") ?: "Sorrento"
        set(v) = p.edit().putString("wCity", v).apply()
    var weatherLat: Double
        get() = p.getFloat("wLat", 28.8066f).toDouble()
        set(v) = p.edit().putFloat("wLat", v.toFloat()).apply()
    var weatherLon: Double
        get() = p.getFloat("wLon", -81.5559f).toDouble()
        set(v) = p.edit().putFloat("wLon", v.toFloat()).apply()
    var fahrenheit: Boolean
        get() = p.getBoolean("fahrenheit", true)
        set(v) = p.edit().putBoolean("fahrenheit", v).apply()
    var music: Boolean
        get() = p.getBoolean("music", true)
        set(v) = p.edit().putBoolean("music", v).apply()
    var stepGoal: Int
        get() = p.getInt("stepGoal", 10000)
        set(v) = p.edit().putInt("stepGoal", v).apply()
    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit().putLong("lastSync", v).apply()

    var alarms: List<Alarm>
        get() = runCatching {
            val a = JSONArray(p.getString("alarms", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Alarm(it.getInt("h"), it.getInt("m"), it.getInt("d"), it.getBoolean("on"), it.optString("label")) } }
        }.getOrDefault(emptyList())
        set(v) = p.edit().putString("alarms", JSONArray(v.map { JSONObject().put("h", it.hour).put("m", it.minute).put("d", it.days).put("on", it.on).put("label", it.label) }).toString()).apply()

    /** Faces this app has put on the watch: design number -> watch face id. */
    var installedFaces: Map<Int, Long>
        get() = runCatching { JSONObject(p.getString("faces", "{}")!!).let { o -> o.keys().asSequence().associate { it.toInt() to o.getLong(it) } } }.getOrDefault(emptyMap())
        set(v) = p.edit().putString("faces", JSONObject(v.mapKeys { it.key.toString() }).toString()).apply()
}

/** days: bit0 = Sunday .. bit6 = Saturday (the watch's own order). */
data class Alarm(val hour: Int, val minute: Int, val days: Int, val on: Boolean, val label: String = "")
