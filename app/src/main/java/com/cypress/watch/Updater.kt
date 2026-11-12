package com.cypress.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub for a newer Cypress Watch, downloads it and hands it to Android's installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/xXxHekticxXx/Cypress-Watch/releases/latest"

    data class State(val latest: String? = null, val url: String? = null, val checking: Boolean = false, val progress: Float? = null, val error: String? = null)
    val state = MutableStateFlow(State())

    fun current(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "1.0.0"

    private fun num(v: String) = v.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
    private fun newer(a: String, b: String): Boolean { val x = num(a); val y = num(b); for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]; return false }

    fun available(c: Context): Boolean = state.value.latest?.let { newer(it, current(c)) } == true

    suspend fun check() = withContext(Dispatchers.IO) {
        state.value = state.value.copy(checking = true, error = null)
        runCatching {
            val con = URL(LATEST).openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.connectTimeout = 10000; con.readTimeout = 15000
            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
            state.value = state.value.copy(latest = j.getString("tag_name").removePrefix("v"), url = apk?.getString("browser_download_url"), checking = false)
        }.onFailure { state.value = state.value.copy(checking = false, error = "Couldn't check for updates (are you online?)") }
    }

    suspend fun install(c: Context) {
        val url = state.value.url ?: return
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            // Android asks once: allow Cypress Watch to install its own updates
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            state.value = state.value.copy(error = "Allow \"Install unknown apps\" for Cypress Watch, then tap Install again.")
            return
        }
        val f = File(c.cacheDir, "update.apk")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                state.value = state.value.copy(progress = 0f, error = null)
                var con = URL(url).openConnection() as HttpURLConnection
                con.instanceFollowRedirects = true
                con.connectTimeout = 15000; con.readTimeout = 30000
                // GitHub sends the file from another host; follow that hop by hand if needed
                if (con.responseCode in 300..399) con = (URL(con.getHeaderField("Location")).openConnection() as HttpURLConnection)
                val total = con.contentLengthLong
                con.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) state.value = state.value.copy(progress = done / total.toFloat())
                        }
                    }
                }
                true
            }.getOrElse { state.value = state.value.copy(progress = null, error = "Download failed: ${it.message}"); false }
        }
        if (!ok) return
        state.value = state.value.copy(progress = null)
        val uri = FileProvider.getUriForFile(c, "com.cypress.watch.files", f)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
