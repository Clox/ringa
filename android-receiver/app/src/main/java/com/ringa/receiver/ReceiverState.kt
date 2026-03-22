package com.ringa.receiver

import android.content.Context
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

object ReceiverPrefs {
    private const val PREFS_NAME = "ringa_receiver_prefs"
    private const val KEY_USE_ROOT_DIRECT_DIAL = "use_root_direct_dial"
    private const val KEY_USE_ROOT_CLIPBOARD_EXPORT = "use_root_clipboard_export"
    private const val KEY_LAST_CLIPBOARD_DEVICE_ID = "last_clipboard_device_id"

    fun shouldUseRootDirectDial(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_ROOT_DIRECT_DIAL, false)
    }

    fun setUseRootDirectDial(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_ROOT_DIRECT_DIAL, enabled)
            .apply()
    }

    fun shouldUseRootClipboardExport(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_ROOT_CLIPBOARD_EXPORT, false)
    }

    fun setUseRootClipboardExport(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_ROOT_CLIPBOARD_EXPORT, enabled)
            .apply()
    }

    fun getLastClipboardDeviceId(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_CLIPBOARD_DEVICE_ID, 0)
    }

    fun setLastClipboardDeviceId(context: Context, deviceId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_CLIPBOARD_DEVICE_ID, deviceId)
            .apply()
    }
}
