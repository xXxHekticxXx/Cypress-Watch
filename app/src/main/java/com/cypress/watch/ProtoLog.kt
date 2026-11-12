package com.cypress.watch

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log of everything sent to and received from the watch. Shareable from the app so it can be fixed. */
object ProtoLog {
    private lateinit var file: File
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init(c: Context) {
        file = File(c.filesDir, "watch-log.txt")
        if (file.length() > 4_000_000) file.writeText("")
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val l = "${fmt.format(Date())} $tag $msg"
        lines.addLast(l)
        while (lines.size > 600) lines.removeFirst()
        runCatching { file.appendText(l + "\n") }
        listeners.toList().forEach { it() }
    }

    fun tx(b: ByteArray) = add(">>", b.hex())
    fun rx(b: ByteArray) = add("<<", b.hex())
    fun info(s: String) = add("--", s)

    @Synchronized fun recent(): List<String> = lines.toList()
    fun file(): File = file
    fun clear() { synchronized(this) { lines.clear() }; runCatching { file.writeText("") }; listeners.toList().forEach { it() } }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
