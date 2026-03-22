package com.ringa.receiver

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Base64

object RootClipboardBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "get-primary-clip-b64" -> {
                    val requestedDeviceId = args.getOrNull(1)?.toIntOrNull() ?: 0
                    print("OK:${readPrimaryClipBase64(requestedDeviceId)}")
                }

                else -> {
                    System.err.println("Unknown command")
                    System.exit(2)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            System.exit(1)
        }
    }

    private fun readPrimaryClipBase64(requestedDeviceId: Int): String {
        val context = systemContext()
        val text = readPrimaryClip(requestedDeviceId)
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        return Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun readPrimaryClip(requestedDeviceId: Int): ClipData? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply {
            isAccessible = true
        }
        val binder = getService.invoke(null, "clipboard") as? IBinder ?: error("clipboard binder unavailable")

        val stubClass = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply {
            isAccessible = true
        }
        val clipboard = asInterface.invoke(null, binder) ?: error("IClipboard unavailable")

        val getPrimaryClip = clipboard.javaClass.getMethod(
            "getPrimaryClip",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return getPrimaryClip.invoke(
            clipboard,
            callingPackage(),
            null,
            0,
            requestedDeviceId,
        ) as? ClipData
    }

    private fun callingPackage(): String {
        return when (Process.myUid()) {
            2000 -> "com.android.shell"
            else -> "android"
        }
    }

    private fun systemContext(): Context {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain").apply {
            isAccessible = true
        }
        val activityThread = systemMain.invoke(null)
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext").apply {
            isAccessible = true
        }
        return getSystemContext.invoke(activityThread) as Context
    }
}
