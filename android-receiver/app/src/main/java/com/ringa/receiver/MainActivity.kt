package com.ringa.receiver

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.HashMap
import java.util.Locale

class MainActivity : Activity() {
    private val listenPort = 9090
    private val nsdServiceType = "_ringa-receiver._tcp."
    private val nsdServiceName = "RingaReceiver"
    private var server: AndroidMessageServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    private lateinit var statusText: TextView
    private lateinit var endpointText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        setContentView(buildUi())
        startServer()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
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
            text = "Start server"
            setOnClickListener { startServer() }
        }

        val stopButton = Button(this).apply {
            text = "Stop server"
            setOnClickListener { stopServer() }
        }

        buttonRow.addView(startButton)
        buttonRow.addView(stopButton)

        val logHeader = TextView(this).apply {
            text = "Received messages"
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

    private fun startServer() {
        if (server != null) {
            updateStatus("Server already running")
            return
        }

        val localIp = getLocalIpv4Address()
        val endpoint = "http://$localIp:$listenPort/message"
        endpointText.text = "Endpoint: $endpoint\nmDNS: $nsdServiceName.$nsdServiceType"

        val newServer = AndroidMessageServer(listenPort) { line, dialNumber ->
            runOnUiThread {
                prependLog(line)
                if (!dialNumber.isNullOrBlank()) {
                    openDialer(dialNumber)
                }
            }
        }

        try {
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = newServer
            registerNsdService(listenPort)
            updateStatus("Listening on LAN + mDNS")
            prependLog("[${nowString()}] Server started on $endpoint")
        } catch (e: Exception) {
            updateStatus("Failed to start server: ${e.message}")
        }
    }

    private fun stopServer() {
        unregisterNsdService()
        server?.stop()
        if (server != null) {
            prependLog("[${nowString()}] Server stopped")
        }
        server = null
        updateStatus("Server stopped")
    }

    private fun updateStatus(text: String) {
        statusText.text = "Status: $text"
    }

    private fun prependLog(line: String) {
        val existing = logText.text.toString()
        logText.text = if (existing.isBlank()) line else "$line\n$existing"
    }

    private fun getLocalIpv4Address(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
        for (networkInterface in Collections.list(interfaces)) {
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            val addresses = networkInterface.inetAddresses
            for (address in Collections.list(addresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress ?: "0.0.0.0"
                }
            }
        }
        return "0.0.0.0"
    }

    private fun registerNsdService(port: Int) {
        val manager = nsdManager ?: return
        unregisterNsdService()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = nsdServiceName
            serviceType = nsdServiceType
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                runOnUiThread {
                    prependLog("[${nowString()}] mDNS registered: ${info.serviceName} ${info.serviceType}")
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    prependLog("[${nowString()}] mDNS registration failed: code=$errorCode")
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                runOnUiThread {
                    prependLog("[${nowString()}] mDNS unregistered: ${info.serviceName}")
                }
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    prependLog("[${nowString()}] mDNS unregistration failed: code=$errorCode")
                }
            }
        }

        nsdRegistrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            prependLog("[${nowString()}] mDNS register error: ${e.message}")
        }
    }

    private fun unregisterNsdService() {
        val manager = nsdManager ?: return
        val listener = nsdRegistrationListener ?: return
        try {
            manager.unregisterService(listener)
        } catch (_: Exception) {
            // Ignore stale listener/unregister errors during shutdown.
        } finally {
            nsdRegistrationListener = null
        }
    }

    private fun openDialer(rawNumber: String) {
        val number = rawNumber.trim()
        if (number.isEmpty()) {
            return
        }

        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(number)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(dialIntent)
            prependLog("[${nowString()}] Opened dialer with: $number")
        } catch (_: ActivityNotFoundException) {
            prependLog("[${nowString()}] No dialer app found for: $number")
        }
    }
}

class AndroidMessageServer(
    port: Int,
    private val onMessageLine: (String, String?) -> Unit,
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return jsonResponse(Response.Status.NO_CONTENT, """{"ok":true}""")
        }

        if (session.uri != "/message") {
            return jsonResponse(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
        }

        if (session.method != Method.POST) {
            return jsonResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                """{"ok":false,"error":"method not allowed"}""",
            )
        }

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val rawBody = files["postData"] ?: "{}"
            val json = JSONObject(rawBody)
            val source = json.optString("source", "terminal-host")
            val dialNumber = extractDialNumber(json)
            val message = extractDisplayMessage(json, rawBody, dialNumber)

            onMessageLine("[${nowString()}] $source: $message", dialNumber)
            jsonResponse(Response.Status.OK, """{"ok":true}""")
        } catch (e: Exception) {
            jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"error":"${escapeForJson(e.message ?: "invalid request")}"}""",
            )
        }
    }

    private fun jsonResponse(status: Response.Status, body: String): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "Content-Type")
            addHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
        }
    }

    private fun extractDialNumber(json: JSONObject): String? {
        val topLevelNumber = json.optString("dial_number", "").trim()
        if (topLevelNumber.isNotEmpty()) {
            return topLevelNumber
        }

        val browserPayload = json.optJSONObject("browser_payload") ?: return null
        val nestedNumber = browserPayload.optString("dial_number", "").trim()
        if (nestedNumber.isNotEmpty()) {
            return nestedNumber
        }
        return null
    }

    private fun extractDisplayMessage(json: JSONObject, fallbackRaw: String, dialNumber: String?): String {
        if (!dialNumber.isNullOrBlank()) {
            return "dial request -> $dialNumber"
        }

        val message = json.optString("message", "").trim()
        if (message.isNotEmpty()) {
            return message
        }

        val browserPayload = json.optJSONObject("browser_payload")
        val browserMessage = browserPayload?.optString("message", "")?.trim().orEmpty()
        if (browserMessage.isNotEmpty()) {
            return browserMessage
        }

        return fallbackRaw
    }
}

private fun nowString(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

private fun escapeForJson(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}
