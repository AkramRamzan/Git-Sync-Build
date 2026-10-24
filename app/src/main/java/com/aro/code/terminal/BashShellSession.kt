package com.aro.code.terminal

import android.content.Context
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
 * Supports two execution modes:
 * 1. Self-contained Mini Linux Container (Alpine ARM64 via PRoot) when installed.
 * 2. Host shell with injected Termux/Android PATH and persistent cwd as fallback.
 */
object BashShellSession {

    private const val TAG = "BashShellSession"
    private const val SHELL_PREFIX = "\uD83D\uDCBB" // 💻

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var isRunning: Boolean = false
        private set

    @Volatile var isLinuxContainerActive: Boolean = false
        private set

    @Volatile var isInstallingLinux: Boolean = false
        private set

    /** Current working directory */
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

    fun start(context: Context? = null) {
        if (isRunning) return
        try {
            val proc: Process
            if (context != null && LinuxSubsystem.isInstalled(context)) {
                // Mode 1: Pure In-App Mini Linux Container (PRoot)
                proc = LinuxSubsystem.spawnProotProcess(context)
                isLinuxContainerActive = true

                TermuxBridgeService.state.addLog("$SHELL_PREFIX 🐧 In-App Mini Linux (Alpine ARM64) Activated!")
                TermuxBridgeService.state.addLog("$SHELL_PREFIX 📦 PRoot sandbox running (fake root ID=0)")
                TermuxBridgeService.state.addLog("$SHELL_PREFIX 📁 Storage mapped at /storage and /root/Download")
                TermuxBridgeService.state.addLog("─".repeat(44))
            } else {
                // Mode 2: Host Android shell
                isLinuxContainerActive = false
                val workingDirFile = File(cwd).let {
                    if (it.exists() && it.isDirectory) it else File("/storage/emulated/0/Download")
                }
                if (!workingDirFile.exists()) workingDirFile.mkdirs()
                cwd = workingDirFile.absolutePath

                val pb = ProcessBuilder("sh").apply {
                    directory(workingDirFile)
                    redirectErrorStream(true)
                    val pathList = listOf(
                        "/data/data/com.termux/files/usr/bin",
                        "/data/data/com.termux/files/usr/bin/applets",
                        "/system/bin",
                        "/system/xbin"
                    )
                    environment()["PATH"] = pathList.joinToString(":")
                    environment()["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
                    environment()["HOME"] = cwd
                    environment()["TERM"] = "xterm-256color"
                }
                proc = pb.start()

                TermuxBridgeService.state.addLog("$SHELL_PREFIX ⚡ Aro Code Terminal (Host Mode)")
                TermuxBridgeService.state.addLog("$SHELL_PREFIX 📁 Working Dir: $cwd")
                if (context != null) {
                    TermuxBridgeService.state.addLog("$SHELL_PREFIX 💡 Tip: Tap [📦 Setup Mini Linux] for 100% In-App Python!")
                }
                TermuxBridgeService.state.addLog("─".repeat(44))
            }

            process = proc
            isRunning = true
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

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
                    isLinuxContainerActive = false
                    TermuxBridgeService.state.addLog("$SHELL_PREFIX [Terminal session ended]")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shell", e)
            TermuxBridgeService.state.addLog("$SHELL_PREFIX [Terminal error: ${e.message}]")
        }
    }

    /** Installs In-App Mini Linux and restarts shell inside container */
    fun installMiniLinux(context: Context) {
        if (isInstallingLinux) return
        isInstallingLinux = true

        scope.launch {
            try {
                TermuxBridgeService.state.addLog("$SHELL_PREFIX 🚀 Initializing Mini Linux Installer...")
                val success = LinuxSubsystem.install(
                    context = context,
                    onLog = { TermuxBridgeService.state.addLog("$SHELL_PREFIX $it") },
                    onProgress = { /* progress */ }
                )
                if (success) {
                    stop()
                    start(context)
                }
            } finally {
                isInstallingLinux = false
            }
        }
    }

    fun sendCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) return

        if (!isRunning) start()

        // 1. Clear screen
        if (cmd == "clear" || cmd == "cls") {
            TermuxBridgeService.state.clearLogs()
            TermuxBridgeService.state.addLog("$SHELL_PREFIX ⚡ Screen Cleared")
            return
        }

        // 2. cd command tracking in host mode
        if (!isLinuxContainerActive && (cmd.startsWith("cd ") || cmd == "cd")) {
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

        // 3. Command execution
        try {
            val promptTag = if (isLinuxContainerActive) "root@arocode:~#" else "aro@code:${File(cwd).name}$"
            TermuxBridgeService.state.addLog("$SHELL_PREFIX $promptTag $cmd")
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
        isLinuxContainerActive = false
        TermuxBridgeService.state.addLog("$SHELL_PREFIX [Terminal stopped]")
    }
}
