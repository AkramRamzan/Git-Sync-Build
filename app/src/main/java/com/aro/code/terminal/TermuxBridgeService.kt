package com.aro.code.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service that monitors and controls the Termux Bridge (server.py).
 * - Periodically pings http://127.0.0.1:8000/v1/health
 * - Fetches live stdout/stderr logs from http://127.0.0.1:8000/v1/logs into in-app UI
 * - Sends Termux RUN_COMMAND intent for background auto-start
 * - Provides 1-tap "Open Termux" fallback
 */
class TermuxBridgeService : Service() {

    companion object {
        private const val TAG = "TermuxBridgeService"
        const val CHANNEL_ID = "aro_bridge_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.aro.code.terminal.START"
        const val ACTION_STOP = "com.aro.code.terminal.STOP"
        const val ACTION_RESTART = "com.aro.code.terminal.RESTART"

        const val DEFAULT_SCRIPT_PATH = "/storage/emulated/0/Download/server.py"
        const val LAUNCH_COMMAND = "python /storage/emulated/0/Download/server.py"

        // Singleton state accessible from anywhere (ViewModel, UI)
        val state = BridgeServiceState()

        /** Helper to copy the launch command to Android clipboard and launch Termux */
        fun openTermux(context: Context) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Termux Command", LAUNCH_COMMAND)
                clipboard.setPrimaryClip(clip)

                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Toast.makeText(context, "Command copied! Termux mein paste karke Enter dabayein.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Termux installed nahi hai! Pehle F-Droid se Termux install karein.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): TermuxBridgeService = this@TermuxBridgeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Aro Code Bridge..."))
        startContinuousMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> startBridge()
            ACTION_RESTART -> {
                serviceScope.launch {
                    appendLog("🔄 Restarting Bridge Server...")
                    delay(500)
                    startBridge()
                }
            }
            ACTION_STOP -> {
                state._status.value = BridgeStatus.STOPPED
                updateNotification("🔴 Bridge Stopped")
                appendLog("🛑 Bridge stopped.")
            }
        }
        return START_STICKY
    }

    /** Continuous health check & log syncer (runs every 2 seconds) */
    private fun startContinuousMonitor() {
        serviceScope.launch {
            var lastSyncedLogs = 0
            while (isActive) {
                val isAlive = pingHealth()
                if (isAlive) {
                    if (state._status.value != BridgeStatus.RUNNING) {
                        state._status.value = BridgeStatus.RUNNING
                        updateNotification("🟢 Bridge Running on :8000")
                        appendLog("✅ Connected to Anti-Gravity Bridge on 127.0.0.1:8000!")
                    }

                    // Sync remote logs from server.py /v1/logs
                    runCatching {
                        val serverLogs = fetchServerLogs()
                        if (serverLogs.isNotEmpty() && serverLogs.size != lastSyncedLogs) {
                            lastSyncedLogs = serverLogs.size
                            serverLogs.forEach { logLine ->
                                if (!state._logs.value.contains(logLine)) {
                                    appendLog(logLine)
                                }
                            }
                        }
                    }
                } else {
                    if (state._status.value == BridgeStatus.RUNNING) {
                        state._status.value = BridgeStatus.STOPPED
                        updateNotification("🔴 Bridge Offline")
                        appendLog("⚠️ Bridge connection lost (127.0.0.1:8000 unreachable).")
                    }
                }
                delay(2000)
            }
        }
    }

    private fun startBridge() {
        serviceScope.launch {
            state._status.value = BridgeStatus.STARTING
            appendLog("🚀 Checking Anti-Gravity Bridge Server...")

            // 1. Check if already running
            if (pingHealth()) {
                state._status.value = BridgeStatus.RUNNING
                updateNotification("🟢 Bridge Running on :8000")
                appendLog("✅ Bridge is already running at http://127.0.0.1:8000!")
                return@launch
            }

            // 2. Try auto-starting via Termux RUN_COMMAND intent (runs in background)
            appendLog("⚡ Sending Termux background launch intent...")
            val sent = sendTermuxRunCommandIntent()

            // 3. Poll for up to 4 seconds for bridge startup
            var connected = false
            for (i in 1..4) {
                delay(1000)
                if (pingHealth()) {
                    connected = true
                    break
                }
            }

            if (connected) {
                state._status.value = BridgeStatus.RUNNING
                updateNotification("🟢 Bridge Running on :8000")
                appendLog("✅ Bridge successfully connected on :8000!")
            } else {
                state._status.value = BridgeStatus.STOPPED
                updateNotification("🔴 Bridge Offline — Tap to start")
                appendLog("─".repeat(44))
                if (sent) {
                    appendLog("ℹ️ Background intent sent. Agar start na ho:")
                } else {
                    appendLog("ℹ️ Termux app nahi mili ya intent block hua:")
                }
                appendLog("👉 Termux mein ye command run karein:")
                appendLog("   $LAUNCH_COMMAND")
                appendLog("💡 'Open Termux' button dabayein — command khud copy ho jayegi.")
                appendLog("─".repeat(44))
            }
        }
    }

    /** Sends Termux RUN_COMMAND intent to run server.py in background */
    private fun sendTermuxRunCommandIntent(): Boolean {
        return runCatching {
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                // Standard modern extras
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(DEFAULT_SCRIPT_PATH))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/storage/emulated/0/Download")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                // Legacy extras compatibility
                putExtra("com.termux.app.run_command.EXTRA_EXECUTABLE", "/data/data/com.termux/files/usr/bin/python")
                putExtra("com.termux.app.run_command.EXTRA_ARGUMENTS", arrayOf(DEFAULT_SCRIPT_PATH))
                putExtra("com.termux.app.run_command.EXTRA_WORKDIR", "/storage/emulated/0/Download")
                putExtra("com.termux.app.run_command.EXTRA_BACKGROUND", true)
            }
            startService(intent)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Termux intent failed: ${e.message}")
            false
        }
    }

    /** Fast 1.5s health ping */
    private fun pingHealth(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:8000/v1/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    /** Fetch server.py log lines via /v1/logs */
    private fun fetchServerLogs(): List<String> {
        return try {
            val url = URL("http://127.0.0.1:8000/v1/logs")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val arr = json.optJSONArray("logs")
                val result = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        result.add(arr.getString(i))
                    }
                }
                result
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendLog(line: String) = state.addLog(line)

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aro Code Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aro Code Bridge Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors the Anti-Gravity Bridge server in background"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

// ─── Bridge State (Singleton observable for Compose) ────────────────────────

enum class BridgeStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

class BridgeServiceState {
    private companion object { const val MAX_LOG_LINES = 500 }

    val _status = MutableStateFlow(BridgeStatus.STOPPED)
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    val _pid = MutableStateFlow<Int?>(null)
    val pid: StateFlow<Int?> = _pid.asStateFlow()

    val isRunning: Boolean get() = _status.value == BridgeStatus.RUNNING

    fun addLog(line: String) {
        val current = _logs.value.toMutableList()
        current.add(line)
        if (current.size > MAX_LOG_LINES) current.removeAt(0)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
