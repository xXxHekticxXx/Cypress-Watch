package com.cypress.watch.svc

import com.cypress.watch.Prefs
import com.cypress.watch.ProtoLog
import com.cypress.watch.ble.Proto
import com.cypress.watch.ble.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 7-day weather from Open-Meteo (free, no account) sent to the watch in °C; the watch shows its own unit. */
object WeatherSync {
    data class Place(val name: String, val region: String, val lat: Double, val lon: Double)
    data class Now(val tempC: Double, val code: Int, val highC: Double, val lowC: Double)
    @Volatile var last: Now? = null

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" + URLEncoder.encode(q, "UTF-8")))
            val r = j.optJSONArray("results") ?: return@runCatching emptyList<Place>()
            (0 until r.length()).map { i -> r.getJSONObject(i).let { Place(it.getString("name"), listOfNotNull(it.optString("admin1").ifBlank { null }, it.optString("country_code")).joinToString(", "), it.getDouble("latitude"), it.getDouble("longitude")) } }
        }.getOrDefault(emptyList())
    }

    fun pushNow() { Watch.scope.launch { push() } }

    suspend fun push() {
        if (!Prefs.weather) return
        val j = withContext(Dispatchers.IO) {
            runCatching {
                JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${Prefs.weatherLat}&longitude=${Prefs.weatherLon}" +
                    "&current=temperature_2m,relative_humidity_2m,weather_code,uv_index&hourly=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,relative_humidity_2m_max" +
                    "&timezone=auto&forecast_days=7&forecast_hours=24&timeformat=unixtime"))
            }.onFailure { ProtoLog.info("weather failed: $it") }.getOrNull()
        } ?: return
        val cur = j.getJSONObject("current"); val daily = j.getJSONObject("daily"); val hourly = j.getJSONObject("hourly")
        val now = System.currentTimeMillis() / 1000
        val t = Math.round(cur.getDouble("temperature_2m")).toInt()
        val code = Proto.wmoToWatch(cur.getInt("weather_code"))
        val hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
        val lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
        last = Now(cur.getDouble("temperature_2m"), cur.getInt("weather_code"), daily.getJSONArray("temperature_2m_max").getDouble(0), daily.getJSONArray("temperature_2m_min").getDouble(0))
        if (!Watch.ready) return
        val m = Watch.maxPacket
        Watch.send(Proto.weatherNow(Prefs.weatherCity, t, hi, lo, cur.optInt("relative_humidity_2m", 60), Math.round(cur.optDouble("uv_index", 3.0)).toInt(), code, now, m))
        val ht = hourly.getJSONArray("time"); val htemp = hourly.getJSONArray("temperature_2m"); val hcode = hourly.getJSONArray("weather_code")
        val hours = (0 until ht.length()).map { Proto.Hour(ht.getLong(it), Math.round(htemp.getDouble(it)).toInt(), Proto.wmoToWatch(hcode.getInt(it))) }
        val dt = daily.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            Proto.Day(
                Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(), Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                Proto.wmoToWatch(daily.getJSONArray("weather_code").getInt(i)), daily.getJSONArray("relative_humidity_2m_max").optInt(i, 60),
                daily.getJSONArray("sunrise").getLong(i), daily.getJSONArray("sunset").getLong(i), dt.getLong(i),
            )
        }
        Watch.send(Proto.weatherForecast(now, hours, days, m))
        ProtoLog.info("weather sent: ${Prefs.weatherCity} ${t}C code=$code")
    }
}
