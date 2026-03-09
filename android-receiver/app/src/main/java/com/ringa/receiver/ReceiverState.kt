package com.ringa.receiver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiverState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var endpoint: String = "not running"

    @Volatile
    var appInForeground: Boolean = false

    private const val maxLogLines = 300
    private val lock = Any()
    private val logLines = ArrayList<String>()

    fun log(line: String) {
        synchronized(lock) {
            logLines.add(0, line)
            if (logLines.size > maxLogLines) {
                logLines.removeAt(logLines.lastIndex)
            }
        }
    }

    fun snapshotLogs(): String {
        synchronized(lock) {
            return logLines.joinToString("\n")
        }
    }
}

fun nowString(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

fun escapeForJson(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}
