package com.aro.code.terminal

import android.content.Context
import android.content.MutableContextWrapper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Individual terminal session backed by a real POSIX PTY (PtySession) and xterm.js.
 */
class ShellSession(
    val id: Int,
    initialTitle: String,
    private val scope: CoroutineScope
) {
    var title by mutableStateOf(initialTitle)
    val logs = mutableStateListOf<String>()
    var isRunning by mutableStateOf(false)
    var isLinuxContainerActive by mutableStateOf(false)
    var cwd by mutableStateOf("/storage/emulated/0/Download")
    var rows by mutableIntStateOf(24)
    var cols by mutableIntStateOf(80)
    var exitCode by mutableIntStateOf(-1)

    val ptySession = PtySession(id)
    var jsInterface: XtermJsInterface? = null
        private set

    private var contextWrapper: MutableContextWrapper? = null
    var webView: WebView? = null
        private set

    init {
        // Mirror all PTY output (stdout & stderr from PRoot/bash) to session logs so
        // diagnostics and errors are preserved in the UI logs and visible immediately
        ptySession.onOutput = { data ->
            try {
                val text = String(data, Charsets.UTF_8)
                for (rawLine in text.lines()) {
                    val line = rawLine.replace("\r", "").trim()
                    if (line.isNotEmpty()) {
                        addLog(line)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Observed by Compose to hide MobileKeyBar when slash palette is shown
    var slashPaletteVisible by mutableStateOf(false)

    fun ensureJsInterface(context: Context): XtermJsInterface {
        val existing = jsInterface
        if (existing != null) return existing

        val iface = XtermJsInterface(
            context = context.applicationContext,
            session = ptySession,
            onReadyCalled = { r, c ->
                rows = r
                cols = c
                if (!isRunning) {
                    start(context, r, c)
                } else {
                    ptySession.resize(r, c)
                }
            },
            onExitCalled = { code ->
                exitCode = code
                isRunning = false
                Log.d("ShellSession", "Session $id exited with code $code")
            },
            onSlashPaletteVisibility = { visible ->
                slashPaletteVisible = visible
            }
        )
        jsInterface = iface
        return iface
    }

    fun start(context: Context, initialRows: Int = 24, initialCols: Int = 80) {
        if (isRunning) return
        rows = initialRows
        cols = initialCols

        try {
            if (LinuxSubsystem.isInstalled(context)) {
                val config = LinuxSubsystem.getProotLaunchConfig(context)
                isLinuxContainerActive = true
                cwd = "/root"

                ptySession.start(config.cmd, config.envp, config.cwd, initialRows, initialCols)
                isRunning = ptySession.isRunning
            } else {
                isLinuxContainerActive = false
                val workingDir = File("/storage/emulated/0/Download").apply { mkdirs() }
                cwd = workingDir.absolutePath

                val cmd = arrayOf("/system/bin/sh", "-i")
                val envMap = HashMap(System.getenv())
                envMap["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
                envMap["HOME"] = workingDir.absolutePath
                envMap["TERM"] = "xterm-256color"
                envMap["COLORTERM"] = "truecolor"
                envMap["LANG"] = "C.UTF-8"
                if (!envMap.containsKey("ANDROID_ROOT")) envMap["ANDROID_ROOT"] = "/system"
                if (!envMap.containsKey("ANDROID_DATA")) envMap["ANDROID_DATA"] = "/data"
                val envp = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

                ptySession.start(cmd, envp, cwd, initialRows, initialCols)
                isRunning = ptySession.isRunning
            }
        } catch (e: Exception) {
            Log.e("ShellSession", "Failed starting session $id", e)
            addLog("[Session $id error: ${e.message}]")
        }
    }

    fun restart(context: Context) {
        stop()
        start(context, rows, cols)
    }

    fun startHost(context: Context) {
        if (isRunning) stop()
        isLinuxContainerActive = false
        val workingDir = File("/storage/emulated/0/Download").apply { mkdirs() }
        cwd = workingDir.absolutePath

        val cmd = arrayOf("/system/bin/sh", "-i")
        val envMap = HashMap(System.getenv())
        envMap["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
        envMap["HOME"] = workingDir.absolutePath
        envMap["TERM"] = "xterm-256color"
        envMap["COLORTERM"] = "truecolor"
        envMap["LANG"] = "C.UTF-8"
        if (!envMap.containsKey("ANDROID_ROOT")) envMap["ANDROID_ROOT"] = "/system"
        if (!envMap.containsKey("ANDROID_DATA")) envMap["ANDROID_DATA"] = "/data"
        val envp = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

        ptySession.start(cmd, envp, cwd, rows, cols)
        isRunning = ptySession.isRunning
    }

    fun sendCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) {
            sendRaw("\r")
            return
        }

        if (cmd == "clear" || cmd == "cls" || cmd == "/clear" || cmd == "/cls") {
            clear()
            return
        }

        // Native Termux-style /model command (displays and switches models inline without hiding UI)
        if (cmd == "/model" || cmd.startsWith("/model ") || cmd == "model" || cmd.startsWith("model ")) {
            val arg = cmd.removePrefix("/model").removePrefix("model").trim()
            val ctx = BashShellSession.appContext
            if (arg.isEmpty()) {
                val currentModel = if (ctx != null) LinuxSubsystem.getSelectedModel(ctx) else "Gemini 3.8 Flash (High)"
                val modelDisplay = buildString {
                    append("\r\n\u001B[1;36m╔══════════════════════════════════════════════════════╗\u001B[0m\r\n")
                    append("\u001B[1;36m║\u001B[0m             \u001B[1;35m🧠 Available AI Models\u001B[0m                   \u001B[1;36m║\u001B[0m\r\n")
                    append("\u001B[1;36m╚══════════════════════════════════════════════════════╝\u001B[0m\r\n")
                    append(" Current Active Model: \u001B[1;32m● $currentModel\u001B[0m\r\n\r\n")
                    append(" [1] \u001B[1;33mGemini 3.8 Flash (High)\u001B[0m   - Ultra-fast & Advanced (Recommended)\r\n")
                    append(" [2] \u001B[1;34mgemini-2.5-pro\u001B[0m            - Deep Reasoning & Complex Coding\r\n")
                    append(" [3] \u001B[1;36mgemini-2.5-flash\u001B[0m          - Ultra Fast & Responsive\r\n")
                    append(" [4] \u001B[1;32mgemini-2.0-flash\u001B[0m          - Stable Generation\r\n")
                    append(" [5] \u001B[1;35mClaude 3.5 Sonnet\u001B[0m         - High-Precision Coding\r\n\r\n")
                    append("\u001B[0;37mTip: To switch model, type:\u001B[0m \u001B[1;32m/model <1-5 or name>\u001B[0m\r\n")
                    append("\u001B[0;37mExample:\u001B[0m \u001B[1;36m/model 2\u001B[0m  or  \u001B[1;36m/model gemini-2.5-pro\u001B[0m\r\n\r\n")
                }
                writeDirect(modelDisplay)
                sendRaw("\r")
                return
            } else {
                val newModel = when (arg) {
                    "1", "3.8", "flash-high" -> "Gemini 3.8 Flash (High)"
                    "2", "2.5-pro", "pro"    -> "gemini-2.5-pro"
                    "3", "2.5-flash", "flash"-> "gemini-2.5-flash"
                    "4", "2.0-flash"         -> "gemini-2.0-flash"
                    "5", "claude", "sonnet"  -> "Claude 3.5 Sonnet"
                    else                     -> arg
                }
                if (ctx != null) {
                    LinuxSubsystem.updateSelectedModel(ctx, newModel)
                }
                writeDirect("\r\n\u001B[1;32m✅ Active Model updated to:\u001B[0m \u001B[1;36m$newModel\u001B[0m\r\n\r\n")
                sendRaw("\r")
                return
            }
        }

        if (title.contains("Shell") || title.contains("Linux") || title.contains("Session")) {
            val shortName = cmd.substringBefore(" ").removePrefix("./").take(12)
            if (shortName.isNotBlank()) title = "$id: $shortName"
        }

        sendRaw(cmd + "\r")
    }

    fun sendCtrlC() {
        sendRaw("\u0003")
    }

    fun sendCtrlZ() {
        sendRaw("\u001A")
    }

    fun sendCtrlD() {
        sendRaw("\u0004")
    }

    fun sendRaw(seq: String) {
        ptySession.write(seq.toByteArray(Charsets.UTF_8))
    }

    fun sendRawBytes(bytes: ByteArray) {
        ptySession.write(bytes)
    }

    /** Sends bracketed paste so TUIs (vim, nano, bash, agy) receive text without mangling */
    fun pasteText(text: String) {
        val bracketed = "\u001B[200~$text\u001B[201~"
        val bytes = bracketed.toByteArray(Charsets.UTF_8)
        val chunkSize = 4096
        for (i in 0 until bytes.size step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(bytes.size)
            ptySession.write(bytes.copyOfRange(i, end))
        }
    }

    fun resize(newRows: Int, newCols: Int) {
        rows = newRows
        cols = newCols
        ptySession.resize(newRows, newCols)
    }

    fun getOrCreateWebView(context: Context): WebView {
        val existing = webView
        if (existing != null) {
            contextWrapper?.baseContext = context
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wrapper = MutableContextWrapper(context)
        contextWrapper = wrapper
        val iface = ensureJsInterface(context)
        val wv = createTerminalWebView(wrapper, iface)
        webView = wv
        return wv
    }

    fun destroyWebView() {
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.stopLoading()
            webView?.destroy()
        } catch (_: Exception) {}
        webView = null
        contextWrapper = null
    }

    fun clear() {
        jsInterface?.clear()
        // Ctrl+L (0x0C) instructs readline / shell to redraw prompt at top of cleared viewport
        sendRaw("\u000C")
    }

    /** Direct write to xterm.js viewport without sending through PTY stdin */
    fun writeDirect(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        jsInterface?.sendOutputToJs(bytes)
    }

    fun refocus() {
        jsInterface?.refocus()
    }

    fun stop() {
        ptySession.stop()
        isRunning = false
        destroyWebView()
    }

    fun addLog(line: String) {
        val cleanLine = line.removePrefix("💻 ")
        logs.add(cleanLine)
        if (logs.size > 800) logs.removeAt(0)
        TermuxBridgeService.state.addLog(if (line.startsWith("💻")) line else "💻 $line")
    }
}

/**
 * Multi-Session Bash Shell Manager (VS Code + Termux style).
 * Supports concurrent background sessions, tab switching, Ctrl+C interrupt.
 */
object BashShellSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var appContext: Context? = null

    val sessions = mutableStateListOf<ShellSession>()
    var activeSessionIndex by mutableIntStateOf(0)

    val activeSession: ShellSession?
        get() = sessions.getOrNull(activeSessionIndex) ?: sessions.firstOrNull()

    val isRunning: Boolean get() = activeSession?.isRunning ?: false
    val isLinuxContainerActive: Boolean get() = activeSession?.isLinuxContainerActive ?: false
    val cwd: String get() = activeSession?.cwd ?: "/storage/emulated/0/Download"

    var isInstallingLinux by mutableStateOf(false)
    var installProgress by mutableIntStateOf(0)
    var installStatusMessage by mutableStateOf("")
    val installLogs = mutableStateListOf<String>()

    fun setWorkingDirectory(newPath: String) {
        activeSession?.let { session ->
            val file = File(newPath)
            if (file.exists() && file.isDirectory) {
                session.cwd = file.absolutePath
                if (session.isRunning) {
                    session.sendRaw("cd \"${session.cwd}\"\r")
                }
            }
        }
    }

    fun start(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        val ctx = context ?: appContext ?: return
        if (sessions.isEmpty()) {
            val first = ShellSession(1, "1: Shell", scope)
            sessions.add(first)
            first.ensureJsInterface(ctx)
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
        if (ctx != null) {
            session.ensureJsInterface(ctx)
            if (!session.isRunning) {
                session.start(ctx)
            }
        }
        activeSessionIndex = sessions.lastIndex
        session.refocus()
        session.jsInterface?.refresh()
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
            activeSession?.refocus()
            activeSession?.jsInterface?.refresh()
        }
    }

    fun switchSession(index: Int) {
        if (index in sessions.indices) {
            activeSessionIndex = index
            activeSession?.refocus()
            activeSession?.jsInterface?.refresh()
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

    fun sendCtrlZ() {
        activeSession?.sendCtrlZ()
    }

    fun sendCtrlD() {
        activeSession?.sendCtrlD()
    }

    fun addLog(line: String) {
        activeSession?.addLog(line)
    }

    fun sendRaw(seq: String) {
        activeSession?.sendRaw(seq)
    }

    fun pasteText(text: String) {
        activeSession?.pasteText(text)
    }

    fun clear() {
        activeSession?.clear()
    }

    fun installMiniLinux(context: Context) {
        if (isInstallingLinux) return
        isInstallingLinux = true
        installProgress = 0
        installStatusMessage = "Starting Ubuntu Linux Installer..."
        installLogs.clear()
        appContext = context.applicationContext

        scope.launch {
            try {
                activeSession?.writeDirect("\r\n\u001B[1;36m[Installer]\u001B[0m Initializing Ubuntu Linux (22.04 LTS ARM64)...\r\n")
                val success = LinuxSubsystem.install(
                    context = context,
                    onLog = { msg ->
                        installStatusMessage = msg
                        installLogs.add(msg)
                        if (installLogs.size > 300) installLogs.removeAt(0)
                        TermuxBridgeService.state.addLog("💻 $msg")
                        activeSession?.writeDirect("\r\n\u001B[32m[Installer]\u001B[0m $msg")
                    },
                    onProgress = { percent ->
                        installProgress = percent
                    }
                )
                if (success) {
                    installStatusMessage = "Ubuntu Linux installed successfully!"
                    installLogs.add("✅ Setup complete! Launching Ubuntu bash shell...")
                    activeSession?.writeDirect("\r\n\u001B[1;32m[Installer]\u001B[0m Setup complete! Restarting terminal session...\r\n")
                    kotlinx.coroutines.delay(1000)
                    stop()
                    kotlinx.coroutines.delay(500)
                    start(context)
                } else {
                    installStatusMessage = "Installation failed!"
                    installLogs.add("❌ Installation failed! Please check internet connection and try again.")
                    activeSession?.writeDirect("\r\n\u001B[1;31m[Installer]\u001B[0m Installation failed! Tap Reinstall to try again.\r\n")
                }
            } catch (e: Exception) {
                val err = "Error: ${e.message}"
                installStatusMessage = err
                installLogs.add("❌ $err")
                activeSession?.writeDirect("\r\n\u001B[1;31m[Installer]\u001B[0m $err\r\n")
            } finally {
                kotlinx.coroutines.delay(2000)
                isInstallingLinux = false
            }
        }
    }

    fun stop() {
        sessions.forEach { it.stop() }
    }
}
