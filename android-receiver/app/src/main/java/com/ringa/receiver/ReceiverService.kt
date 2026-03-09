package com.ringa.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityOptions
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.HashMap

class ReceiverService : Service() {
    private val listenPort = 9090
    private val nsdServiceType = "_ringa-receiver._tcp."
    private val nsdServiceName = "RingaReceiver"
    private var server: AndroidMessageServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startReceiverIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopReceiver()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startReceiverIfNeeded() {
        try {
            val statusNotification = buildStatusNotification("Starting listener...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    statusNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, statusNotification)
            }
        } catch (e: Exception) {
            ReceiverState.running = false
            ReceiverState.endpoint = "not running"
            ReceiverState.log("[${nowString()}] Foreground start blocked: ${e.message}")
            stopSelf()
            return
        }

        if (server != null) {
            updateStatusNotification("Listener already running")
            return
        }

        val localIp = getLocalIpv4Address()
        val endpoint = "http://$localIp:$listenPort/message"
        ReceiverState.endpoint = endpoint
        acquireLocks()

        val newServer = AndroidMessageServer(listenPort) { line, dialNumber ->
            ReceiverState.log(line)
            if (!dialNumber.isNullOrBlank()) {
                handleDialRequest(dialNumber)
            }
        }

        try {
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = newServer
            registerNsdService(listenPort)
            ReceiverState.running = true
            ReceiverState.log("[${nowString()}] Server started on $endpoint")
            updateStatusNotification("Listening on $endpoint")
        } catch (e: Exception) {
            ReceiverState.running = false
            ReceiverState.log("[${nowString()}] Start failed: ${e.message}")
            updateStatusNotification("Failed: ${e.message}")
        }
    }

    private fun stopReceiver() {
        unregisterNsdService()
        server?.stop()
        server = null
        releaseLocks()
        ReceiverState.running = false
        ReceiverState.endpoint = "not running"
        ReceiverState.log("[${nowString()}] Server stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun scheduleRestart() {
        val restartIntent = Intent(this, BootReceiver::class.java).apply {
            action = ACTION_RESTART
        }
        val restartPendingIntent = PendingIntent.getBroadcast(
            this,
            200,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + 1500L
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            restartPendingIntent,
        )
    }

    private fun acquireLocks() {
        if (cpuWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            cpuWakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RingaReceiver::CpuWakeLock",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "RingaReceiver::WifiLock",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        cpuWakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        cpuWakeLock = null

        wifiLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wifiLock = null
    }

    private fun handleDialRequest(rawNumber: String) {
        val number = rawNumber.trim()
        if (number.isEmpty()) {
            return
        }

        if (!ReceiverState.appInForeground) {
            ReceiverState.log("[${nowString()}] Dial request queued via notification: $number")
            postDialNotification(number)
            return
        }

        try {
            launchDialerViaPendingIntent(number)
            ReceiverState.log("[${nowString()}] Opened dialer with: $number")
        } catch (e: Exception) {
            ReceiverState.log("[${nowString()}] Dialer launch failed: ${e.message}")
            postDialNotification(number)
        }
    }

    private fun launchDialerViaPendingIntent(number: String) {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(number)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingDial = PendingIntent.getActivity(
            this,
            101,
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            pendingDial.send(
                this,
                0,
                null,
                null,
                null,
                null,
                options.toBundle(),
            )
            return
        }

        pendingDial.send()
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
                ReceiverState.log("[${nowString()}] mDNS registered: ${info.serviceName} ${info.serviceType}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                ReceiverState.log("[${nowString()}] mDNS registration failed: code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                ReceiverState.log("[${nowString()}] mDNS unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                ReceiverState.log("[${nowString()}] mDNS unregistration failed: code=$errorCode")
            }
        }

        nsdRegistrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            ReceiverState.log("[${nowString()}] mDNS register error: ${e.message}")
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ringa Receiver",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
        val dialChannel = NotificationChannel(
            DIAL_CHANNEL_ID,
            "Ringa Dial Requests",
            NotificationManager.IMPORTANCE_HIGH,
        )
        dialChannel.description = "Tap to open the dialer for incoming browser dial requests."
        nm.createNotificationChannel(dialChannel)
    }

    private fun buildStatusNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, ReceiverService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Ringa Receiver")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateStatusNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildStatusNotification(contentText))
    }

    private fun postDialNotification(number: String) {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(number)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingDial = PendingIntent.getActivity(
            this,
            100,
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, DIAL_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Dial request")
            .setContentText("Tap to dial $number")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setAutoCancel(true)
            .setContentIntent(pendingDial)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(DIAL_NOTIFICATION_ID, notification)
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

    companion object {
        private const val CHANNEL_ID = "ringa_receiver"
        private const val DIAL_CHANNEL_ID = "ringa_dial"
        private const val NOTIFICATION_ID = 1001
        private const val DIAL_NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.ringa.receiver.action.START"
        private const val ACTION_STOP = "com.ringa.receiver.action.STOP"
        const val ACTION_RESTART = "com.ringa.receiver.action.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReceiverService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
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
