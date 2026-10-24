package com.aro.code.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Singleton in-app Bash shell session (VS Code Integrated Terminal Engine).
 * Runs `sh` in a persistent working directory with Termux/Python environment variables.
 * Supports:
 * - Dynamic directory navigation (`cd`, `pwd`)
 * - Python 3 & Pip execution
 * - Output streaming to BridgeServiceState logs
 * - `clear` command handling
 */
object BashShellSession {

    private const val TAG = "BashShellSession"
    private const val SHELL_PREFIX = "\uD83D\uDCBB" // 💻

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var isRunning: Boolean = false
        private set

    /** Current working directory (defaults to Download or project folder) */
    @Volatile var cwd: String = resolveInitialWorkingDir()
        private set

    private var onOutput: ((String) -> Unit)? = null

    fun setOutputCallback(cb: (String) -> Unit) {
        onOutput = cb
    }

    private fun resolveInitialWorkingDir(): String {
        val candidates = listOf(
            "/storage/emulated/0/Download/com.aro.code",
            "/storage/emulated/0/Download",
            "/sdcard/Download",
            "/sdcard"
        )
        return candidates.firstOrNull { File(it).exists() && File(it).isDirectory }
            ?: "/storage/emulated/0/Download"
    }

    fun setWorkingDirectory(newPath: String) {
        val file = File(newPath)
        if (file.exists() && file.isDirectory) {
            cwd = file.absolutePath
            if (isRunning) {
                runCatching {
                    writer?.write("cd \"$cwd\"\n")
                    writer?.flush()
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        try {
            val workingDirFile = File(cwd).let {
                if (it.exists() && it.isDirectory) it else File("/storage/emulated/0/Download")
            }
            if (!workingDirFile.exists()) {
                workingDirFile.mkdirs()
            }
            cwd = workingDirFile.absolutePath

            val pb = ProcessBuilder("sh").apply {
                directory(workingDirFile)
                redirectErrorStream(true)

                // Inject Termux Python/Pip & System bin paths
                val pathList = listOf(
                    "/data/data/com.termux/files/usr/bin",
                    "/data/data/com.termux/files/usr/bin/applets",
                    "/data/user/0/com.termux/files/usr/bin",
                    "/system/bin",
                    "/system/xbin"
                )
                environment()["PATH"] = pathList.joinToString(":")
                environment()["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
                environment()["PREFIX"] = "/data/data/com.termux/files/usr"
                environment()["HOME"] = cwd
                environment()["TERM"] = "xterm-256color"
                environment()["PYTHONUNBUFFERED"] = "1"
            }

            val proc = pb.start()
            process = proc
            isRunning = true
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

            // Initial banner
            TermuxBridgeService.state.addLog("$SHELL_PREFIX ⚡ Aro Code Integrated Terminal (VS Code Mode)")
            TermuxBridgeService.state.addLog("$SHELL_PREFIX 📁 Working Dir: $cwd")
            TermuxBridgeService.state.addLog("$SHELL_PREFIX 🐍 Python & Pip paths mapped")
            TermuxBridgeService.state.addLog("─".repeat(44))

            // Stream stdout & stderr
            scope.launch {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                try {
                    var line = reader.readLine()
                    while (isActive && line != null) {
                        val msg = "$SHELL_PREFIX $line"
                        TermuxBridgeService.state.addLog(msg)
                        onOutput?.invoke(msg)
                        line = reader.readLine()
                    }
                } catch (_: Exception) {
                } finally {
                    isRunning = false
                    TermuxBridgeService.state.addLog("$SHELL_PREFIX [Terminal session ended]")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shell", e)
            TermuxBridgeService.state.addLog("$SHELL_PREFIX [Terminal error: ${e.message}]")
        }
    }

    fun sendCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) return

        if (!isRunning) start()

        // 1. Clear screen command
        if (cmd == "clear" || cmd == "cls") {
            TermuxBridgeService.state.clearLogs()
            TermuxBridgeService.state.addLog("$SHELL_PREFIX ⚡ Aro Code Terminal — Screen Cleared")
            return
        }

        // 2. cd command tracking
        if (cmd.startsWith("cd ") || cmd == "cd") {
            val target = cmd.removePrefix("cd").trim()
            val newDir = when {
                target.isEmpty() || target == "~" -> File("/storage/emulated/0/Download")
                target == ".." -> File(cwd).parentFile ?: File(cwd)
                target.startsWith("/") -> File(target)
                else -> File(cwd, target)
            }
            if (newDir.exists() && newDir.isDirectory) {
                cwd = newDir.absolutePath
                TermuxBridgeService.state.addLog("$SHELL_PREFIX aro@code:${File(cwd).name}$ cd \"$cwd\"")
                try {
                    writer?.write("cd \"$cwd\"\n")
                    writer?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "cd write failed", e)
                }
            } else {
                TermuxBridgeService.state.addLog("$SHELL_PREFIX aro@code:${File(cwd).name}$ cd: no such directory: $target")
            }
            return
        }

        // 3. Regular command execution
        try {
            TermuxBridgeService.state.addLog("$SHELL_PREFIX aro@code:${File(cwd).name}$ $cmd")
            writer?.write(cmd)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed", e)
            TermuxBridgeService.state.addLog("$SHELL_PREFIX [Command error: ${e.message}]")
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
        TermuxBridgeService.state.addLog("$SHELL_PREFIX [Terminal stopped]")
    }
}
