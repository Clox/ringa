package com.ringa.receiver

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var statusText: TextView
    private lateinit var endpointText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestRuntimePermissionsIfNeeded()
        ReceiverService.start(this)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        ReceiverState.appInForeground = true
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        ReceiverState.appInForeground = false
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }

        val title = TextView(this).apply {
            text = "Ringa Android Receiver"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 18, 0, 4)
        }

        endpointText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 4, 0, 18)
            typeface = Typeface.MONOSPACE
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val startButton = Button(this).apply {
            text = "Start listener"
            setOnClickListener {
                ReceiverService.start(this@MainActivity)
                refreshUi()
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop listener"
            setOnClickListener {
                ReceiverService.stop(this@MainActivity)
                refreshUi()
            }
        }

        buttonRow.addView(startButton)
        buttonRow.addView(stopButton)

        val logHeader = TextView(this).apply {
            text = "Received events"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 8)
        }

        logText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setPadding(10, 10, 10, 10)
        }

        content.addView(title)
        content.addView(statusText)
        content.addView(endpointText)
        content.addView(buttonRow)
        content.addView(logHeader)
        content.addView(logText, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(content)
        return root
    }

    private fun refreshUi() {
        statusText.text = if (ReceiverState.running) {
            "Status: Listening in foreground service"
        } else {
            "Status: Stopped"
        }

        endpointText.text = "Endpoint: ${ReceiverState.endpoint}\nmDNS: RingaReceiver._ringa-receiver._tcp."
        logText.text = ReceiverState.snapshotLogs()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                // Some OEMs block this intent; app still runs in foreground service mode.
            }
        }
    }
}
