package com.aro.code.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Individual terminal session representing one shell process (container or host).
 */
class ShellSession(
    val id: Int,
    initialTitle: String,
    private val scope: CoroutineScope
) {
    var title by mutableStateOf(initialTitle)
    val logs = mutableStateListOf<String>()
    var process: Process? = null
    var writer: BufferedWriter? = null
    var isRunning by mutableStateOf(false)
    var isLinuxContainerActive by mutableStateOf(false)
    var cwd by mutableStateOf("/storage/emulated/0/Download")

    fun start(context: Context) {
        if (isRunning) return
        try {
            val proc: Process
            if (LinuxSubsystem.isInstalled(context)) {
                proc = LinuxSubsystem.spawnProotProcess(context)
                isLinuxContainerActive = true
                addLog("💻 🐧 [Session $id] In-App Ubuntu Linux (ARM64 GLIBC) Container Started")
            } else {
                isLinuxContainerActive = false
                val workingDirFile = File(cwd).let {
                    if (it.exists() && it.isDirectory) it else File("/storage/emulated/0/Download")
                }
                if (!workingDirFile.exists()) workingDirFile.mkdirs()
                cwd = workingDirFile.absolutePath

                val pb = ProcessBuilder("sh").apply {
                    directory(workingDirFile)
                    redirectErrorStream(true)
                    environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
                    environment()["HOME"] = cwd
                    environment()["TERM"] = "xterm-256color"
                }
                proc = pb.start()
                addLog("💻 ⚡ [Session $id] Host Terminal Started")
            }

            process = proc
            isRunning = true
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

            scope.launch {
                val stream = proc.inputStream
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val buffer = CharArray(1024)
                val lineBuffer = StringBuilder()
                var lastWasCr = false

                try {
                    while (isActive) {
                        val count = reader.read(buffer)
                        if (count == -1) break
                        for (i in 0 until count) {
                            val ch = buffer[i]
                            if (ch == '\r') {
                                if (lineBuffer.isNotEmpty()) {
                                    val text = lineBuffer.toString().trimEnd()
                                    if (text.isNotBlank()) {
                                        updateOrAddLog("💻 $text", replaceLast = lastWasCr)
                                    }
                                    lineBuffer.clear()
                                }
                                lastWasCr = true
                            } else if (ch == '\n') {
                                if (lineBuffer.isNotEmpty()) {
                                    val text = lineBuffer.toString().trimEnd()
                                    if (text.isNotBlank()) {
                                        updateOrAddLog("💻 $text", replaceLast = lastWasCr)
                                    }
                                    lineBuffer.clear()
                                }
                                lastWasCr = false
                            } else {
                                lineBuffer.append(ch)
                            }
                        }

                        // Flush trailing prompt (e.g., "root@arocode:~# ") when no more immediate bytes are available
                        if (lineBuffer.isNotEmpty() && !reader.ready()) {
                            val text = lineBuffer.toString().trimEnd()
                            if (text.isNotBlank()) {
                                updateOrAddLog("💻 $text", replaceLast = lastWasCr)
                                lineBuffer.clear()
                                lastWasCr = false
                            }
                        }
                    }
                    if (lineBuffer.isNotEmpty()) {
                        val text = lineBuffer.toString().trimEnd()
                        if (text.isNotBlank()) {
                            updateOrAddLog("💻 $text", replaceLast = lastWasCr)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    isRunning = false
                    addLog("💻 [Session $id stopped]")
                }
            }
        } catch (e: Exception) {
            addLog("💻 [Session $id error: ${e.message}]")
        }
    }

    fun sendCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) return

        if (cmd == "clear" || cmd == "cls") {
            logs.clear()
            addLog("💻 ⚡ Screen Cleared")
            return
        }

        // Dynamically name tab after command (e.g., "server.py", "agy", "python")
        if (title.contains("Shell") || title.contains("Linux") || title.contains("Session")) {
            val shortName = cmd.substringBefore(" ").removePrefix("./").take(12)
            if (shortName.isNotBlank()) {
                title = "$id: $shortName"
            }
        }

        val promptTag = if (isLinuxContainerActive) "root@arocode:~#" else "aro@code$"
        if (logs.isNotEmpty() && logs.last().trim() == "💻 $promptTag".trim()) {
            logs[logs.lastIndex] = "💻 $promptTag $cmd"
        } else if (!isLinuxContainerActive) {
            addLog("💻 $promptTag $cmd")
        }
        try {
            writer?.write(cmd)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            addLog("💻 [Command error: ${e.message}]")
        }
    }

    fun sendCtrlC() {
        try {
            writer?.write("\u0003")
            writer?.flush()
            addLog("💻 ^C")
        } catch (_: Exception) {}
    }

    /**
     * Send raw bytes directly to stdin without adding newline or echo.
     * Used for TUI navigation: arrow keys, Enter, ESC, etc.
     */
    fun sendRaw(seq: String) {
        try {
            writer?.write(seq)
            writer?.flush()
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            writer?.write("\u0003")
            writer?.flush()
            writer?.close()
            process?.destroy()
        } catch (_: Exception) {}
        isRunning = false
        process = null
        writer = null
    }

    private fun updateOrAddLog(line: String, replaceLast: Boolean) {
        val stripped = stripAnsi(line)
        if (replaceLast && logs.isNotEmpty() && logs.last().startsWith("💻")) {
            logs[logs.lastIndex] = stripped
        } else {
            addLog(stripped)
        }
    }

    /** Strip ANSI/VT100 escape sequences and query artifacts so text and URLs are clean. */
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;?><= !\"#\\$%&'()*+,-./]*[A-Za-z~@]"), "")   // CSI sequences
                   .replace(Regex("\\[[?>][0-9;]*\\\$?[a-zA-Z0-9;]*"), "")                     // Stray query artifacts like [>q, [?2026$p
                   .replace(Regex("\u001B[()][AB012]"), "")                                     // Charset designators
                   .replace(Regex("\u001B[78DEZHMNO]"), "")                                     // Single-char ESC sequences
                   .replace(Regex("\u001B\\][^\u0007]*(\u0007|\\\\)"), "")                         // OSC sequences
    }

    private fun addLog(line: String) {
        logs.add(line)
        if (logs.size > 800) logs.removeAt(0)
        TermuxBridgeService.state.addLog(line)
    }
}

/**
 * Multi-Session Bash Shell Manager (VS Code + Termux style).
 * Supports concurrent background sessions, tab switching, Ctrl+C interrupt.
 */
object BashShellSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null

    val sessions = mutableStateListOf<ShellSession>()
    var activeSessionIndex by mutableIntStateOf(0)

    val activeSession: ShellSession?
        get() = sessions.getOrNull(activeSessionIndex) ?: sessions.firstOrNull()

    val isRunning: Boolean get() = activeSession?.isRunning ?: false
    val isLinuxContainerActive: Boolean get() = activeSession?.isLinuxContainerActive ?: false
    val cwd: String get() = activeSession?.cwd ?: "/storage/emulated/0/Download"

    @Volatile var isInstallingLinux: Boolean = false
        private set

    fun setWorkingDirectory(newPath: String) {
        activeSession?.let { session ->
            val file = File(newPath)
            if (file.exists() && file.isDirectory) {
                session.cwd = file.absolutePath
                if (session.isRunning) {
                    runCatching {
                        session.writer?.write("cd \"${session.cwd}\"\n")
                        session.writer?.flush()
                    }
                }
            }
        }
    }

    fun start(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        val ctx = context ?: appContext ?: return
        if (sessions.isEmpty()) {
            val first = ShellSession(1, "1: server.py", scope)
            sessions.add(first)
            first.start(ctx)
            activeSessionIndex = 0
        } else {
            activeSession?.let { if (!it.isRunning) it.start(ctx) }
        }
    }

    fun createSession(context: Context? = null, customTitle: String? = null): ShellSession {
        if (context != null) appContext = context.applicationContext
        val ctx = context ?: appContext
        val nextId = (sessions.maxOfOrNull { it.id } ?: 0) + 1
        val defaultTitle = "$nextId: Shell"
        val session = ShellSession(nextId, customTitle ?: defaultTitle, scope)
        sessions.add(session)
        if (ctx != null) session.start(ctx)
        activeSessionIndex = sessions.lastIndex
        return session
    }

    fun closeSession(index: Int) {
        if (sessions.size <= 1) return
        if (index in sessions.indices) {
            val session = sessions.removeAt(index)
            session.stop()
            if (activeSessionIndex >= sessions.size) {
                activeSessionIndex = sessions.size - 1
            }
        }
    }

    fun switchSession(index: Int) {
        if (index in sessions.indices) {
            activeSessionIndex = index
        }
    }

    fun sendCommand(cmd: String) {
        if (sessions.isEmpty()) {
            appContext?.let { start(it) }
        }
        activeSession?.sendCommand(cmd)
    }

    fun sendCtrlC() {
        activeSession?.sendCtrlC()
    }

    /** Send raw escape sequence to active session's stdin (for TUI navigation). */
    fun sendRaw(seq: String) {
        activeSession?.sendRaw(seq)
    }

    fun installMiniLinux(context: Context) {
        if (isInstallingLinux) return
        isInstallingLinux = true
        appContext = context.applicationContext

        scope.launch {
            try {
                TermuxBridgeService.state.addLog("💻 🚀 Initializing Ubuntu Linux Installer...")
                val success = LinuxSubsystem.install(
                    context = context,
                    onLog = { TermuxBridgeService.state.addLog("💻 $it") },
                    onProgress = { /* progress */ }
                )
                if (success) {
                    stop()
                    kotlinx.coroutines.delay(300)
                    start(context)
                }
            } finally {
                isInstallingLinux = false
            }
        }
    }

    fun stop() {
        sessions.forEach { it.stop() }
    }
}
