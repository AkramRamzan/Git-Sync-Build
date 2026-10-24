package com.aro.code.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Foreground service that keeps the Termux Bridge (server.py) alive.
 * Auto-launches on MainActivity.onCreate, streams stdout/stderr logs
 * to BridgeServiceState for real-time display in the in-app terminal.
 */
class TermuxBridgeService : Service() {

    companion object {
        private const val TAG = "TermuxBridgeService"
        const val CHANNEL_ID = "aro_bridge_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.aro.code.terminal.START"
        const val ACTION_STOP = "com.aro.code.terminal.STOP"
        const val ACTION_RESTART = "com.aro.code.terminal.RESTART"

        // Singleton state accessible from anywhere (ViewModel, UI)
        val state = BridgeServiceState()
    }

    inner class LocalBinder : Binder() {
        fun getService(): TermuxBridgeService = this@TermuxBridgeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridgeProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Aro Code Bridge..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> startBridgeProcess()
            ACTION_RESTART -> {
                stopBridgeProcess()
                serviceScope.launch { delay(500); startBridgeProcess() }
            }
            ACTION_STOP -> {
                stopBridgeProcess()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startBridgeProcess() {
        if (bridgeProcess?.isAlive == true) {
            appendLog("ℹ️ Bridge already running — skipping restart.")
            return
        }
        state._status.value = BridgeStatus.STARTING
        appendLog("🚀 Starting Anti-Gravity Bridge Server v2.4...")

        serviceScope.launch {
            runCatching {
                // Detect server.py in phone Download folder
                val serverScriptCandidates = listOf(
                    "/storage/emulated/0/Download/server.py",
                    "/sdcard/Download/server.py",
                    "${filesDir.absolutePath}/server.py"
                )
                val serverScript = serverScriptCandidates.firstOrNull {
                    java.io.File(it).exists()
                } ?: run {
                    state._status.value = BridgeStatus.ERROR
                    appendLog("❌ server.py not found in Downloads folder!")
                    appendLog("👉 Please copy server.py to /storage/emulated/0/Download/")
                    updateNotification("❌ Bridge Error: server.py not found")
                    return@launch
                }

                // Try python3, then python
                val pythonCandidates = listOf(
                    "/data/data/com.termux/files/usr/bin/python3",
                    "/data/data/com.termux/files/usr/bin/python",
                    "python3",
                    "python"
                )
                val python = pythonCandidates.firstOrNull { cmd ->
                    runCatching { Runtime.getRuntime().exec(arrayOf(cmd, "--version")) }.isSuccess
                } ?: "python3"

                appendLog("🐍 Using Python: $python")
                appendLog("📄 Script: $serverScript")

                val pb = ProcessBuilder(python, serverScript)
                pb.environment()["PYTHONUNBUFFERED"] = "1"
                pb.redirectErrorStream(true) // Merge stdout+stderr into one stream
                bridgeProcess = pb.start()
                state._process.value = bridgeProcess
                state._status.value = BridgeStatus.RUNNING
                state._pid.value = bridgeProcess!!.pid().toInt()

                updateNotification("🟢 Bridge Running on :8000")
                appendLog("✅ Bridge started! PID: ${bridgeProcess!!.pid()}")
                appendLog("📍 http://127.0.0.1:8000 — Ready for Aro Code")
                appendLog("─".repeat(44))

                // Stream bridge output in real-time
                val reader = BufferedReader(InputStreamReader(bridgeProcess!!.inputStream))
                var line: String?
                while (isActive && reader.readLine().also { line = it } != null) {
                    appendLog(line ?: "")
                }

                // Process exited
                val exitCode = bridgeProcess!!.waitFor()
                state._status.value = BridgeStatus.STOPPED
                appendLog("─".repeat(44))
                appendLog("⚠️ Bridge stopped (exit code: $exitCode)")
                updateNotification("⚠️ Bridge Stopped — Tap to restart")

            }.onFailure { e ->
                state._status.value = BridgeStatus.ERROR
                appendLog("❌ Error: ${e.message}")
                updateNotification("❌ Bridge Error")
                Log.e(TAG, "Bridge error", e)
            }
        }
    }

    private fun stopBridgeProcess() {
        bridgeProcess?.destroy()
        bridgeProcess = null
        state._process.value = null
        state._status.value = BridgeStatus.STOPPED
        appendLog("🛑 Bridge server stopped.")
        updateNotification("⚪ Bridge Stopped")
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
            description = "Keeps the Anti-Gravity Bridge server running in background"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopBridgeProcess()
        serviceScope.cancel()
        super.onDestroy()
    }
}

// ─── Bridge State (Singleton observable for Compose) ────────────────────────

enum class BridgeStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

class BridgeServiceState {
    private companion object { const val MAX_LOG_LINES = 400 }

    val _status = MutableStateFlow(BridgeStatus.STOPPED)
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    val _pid = MutableStateFlow<Int?>(null)
    val pid: StateFlow<Int?> = _pid.asStateFlow()

    val _process = MutableStateFlow<Process?>(null)
    val process: StateFlow<Process?> = _process.asStateFlow()

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
