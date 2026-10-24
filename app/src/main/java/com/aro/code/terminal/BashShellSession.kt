package com.aro.code.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Singleton in-app Bash shell session.
 * Spawns a `sh` process, pipes stdin/stdout, and streams output
 * lines into [TermuxBridgeService.state] logs (prefixed with [SHELL]).
 */
object BashShellSession {

    private const val TAG = "BashShellSession"
    private const val SHELL_PREFIX = "\uD83D\uDCBB" // 💻

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var isRunning: Boolean = false
        private set

    // Output callback for the UI
    private var onOutput: ((String) -> Unit)? = null

    fun setOutputCallback(cb: (String) -> Unit) {
        onOutput = cb
    }

    fun start() {
        if (isRunning) return
        try {
            val pb = ProcessBuilder("sh").apply {
                redirectErrorStream(true)
                environment()["TERM"] = "dumb"
            }
            val proc = pb.start()
            process = proc
            isRunning = true
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

            // Stream stdout → state logs
            scope.launch {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                try {
                    var line: String?
                    while (isActive && reader.readLine().also { line = it } != null) {
                        val msg = "$SHELL_PREFIX ${line!!}"
                        TermuxBridgeService.state.addLog(msg)
                        onOutput?.invoke(msg)
                    }
                } catch (_: Exception) {
                } finally {
                    isRunning = false
                    TermuxBridgeService.state.addLog("$SHELL_PREFIX [Shell session ended]")
                }
            }

            TermuxBridgeService.state.addLog("$SHELL_PREFIX [Bash shell started]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shell", e)
            TermuxBridgeService.state.addLog("$SHELL_PREFIX [Shell error: ${e.message}]")
        }
    }

    fun sendCommand(cmd: String) {
        if (!isRunning) start()
        try {
            TermuxBridgeService.state.addLog("$SHELL_PREFIX \$ $cmd")
            writer?.apply {
                write(cmd)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed", e)
        }
    }

    fun stop() {
        try {
            writer?.close()
            process?.destroy()
        } catch (_: Exception) {}
        isRunning = false
        process = null
        writer = null
        TermuxBridgeService.state.addLog("$SHELL_PREFIX [Shell stopped]")
    }
}
