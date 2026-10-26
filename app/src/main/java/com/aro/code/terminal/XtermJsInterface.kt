package com.aro.code.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * XtermJsInterface — JavascriptInterface bridge between xterm.js (in WebView)
 * and a [PtySession].
 *
 * Direction A — JS → PTY:
 *   xterm.js calls AroTerminal.sendInput(base64)
 *   → decoded bytes written to PtySession.write()
 *
 * Direction B — PTY → JS:
 *   PtySession.onOutput callback calls sendOutput(bytes)
 *   → base64-encodes, calls webView.evaluateJavascript("writeToTerminal(…)")
 *
 * Direction C — Resize:
 *   JS calls AroTerminal.onResize(rows, cols)
 *   → PtySession.resize(rows, cols)
 *
 * Direction D — Dynamic Completions:
 *   JS calls AroTerminal.queryCompletions(prefix)
 *   → runs bash compgen in PRoot, returns JSON array to JS callback
 *
 * Security: this bridge is added only to the single terminal WebView and the
 * WebView loads only bundled assets (no navigation allowed). Shell access is
 * already restricted to the PRoot container.
 */
class XtermJsInterface(
    private val context: Context,
    val session: PtySession,
    private val onReadyCalled: (rows: Int, cols: Int) -> Unit = { _, _ -> },
    private val onExitCalled:  (code: Int) -> Unit = {},
    /** Called when JS palette shows/hides — used to hide MobileKeyBar in Compose */
    val onSlashPaletteVisibility: ((visible: Boolean) -> Unit)? = null
) {
    companion object {
        const val INTERFACE_NAME = "AroTerminal"
        private const val TAG = "XtermJsInterface"
    }

    var webView: WebView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        /* Wire PTY output → xterm.js (while preserving existing listeners like logger) */
        val prevOnOutput = session.onOutput
        session.onOutput = { bytes ->
            prevOnOutput?.invoke(bytes)
            sendOutputToJs(bytes)
        }
        session.onExit = { code ->
            scope.launch(Dispatchers.Main) {
                onExitCalled(code)
            }
        }
    }

    fun attachWebView(wv: WebView) {
        this.webView = wv
    }

    // ── Called by JS: terminal is ready, provide initial size ─────────────

    @JavascriptInterface
    fun onReady(rows: Int, cols: Int) {
        Log.i(TAG, "xterm.js ready: ${rows}×${cols}")
        onReadyCalled(rows, cols)
    }

    // ── Called by JS: terminal resized (keyboard, rotation, split) ─────────

    @JavascriptInterface
    fun onResize(rows: Int, cols: Int) {
        Log.d(TAG, "resize → ${rows}×${cols}")
        session.resize(rows, cols)
    }

    // ── Called by JS: user typed or pasted (base64-encoded bytes) ──────────

    @JavascriptInterface
    fun sendInput(base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            session.write(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "sendInput decode error: ${e.message}")
        }
    }

    // ── Called by JS: user tapped a link ───────────────────────────────────

    @JavascriptInterface
    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openUrl failed: $url — ${e.message}")
        }
    }

    // ── Called by JS: slash palette visibility changed → show/hide MobileKeyBar ──

    @JavascriptInterface
    fun onSlashPaletteShown() {
        scope.launch(Dispatchers.Main) {
            onSlashPaletteVisibility?.invoke(true)
        }
    }

    @JavascriptInterface
    fun onSlashPaletteHidden() {
        scope.launch(Dispatchers.Main) {
            onSlashPaletteVisibility?.invoke(false)
        }
    }

    // ── Called by JS: query dynamic bash completions for slash commands ─────
    // Runs `compgen -c <prefix>` inside PRoot asynchronously, then delivers
    // result back to JS via window.aroCompletionsCallback(jsonArray)

    @JavascriptInterface
    fun queryCompletions(prefix: String) {
        scope.launch(Dispatchers.IO) {
            val results = runBashCompgen(prefix.trimStart('/').take(32))
            val json = JSONArray(results).toString()
            scope.launch(Dispatchers.Main) {
                webView?.evaluateJavascript("window.aroCompletionsCallback && window.aroCompletionsCallback($json)", null)
            }
        }
    }

    /**
     * Run `compgen -c <prefix>` inside the PRoot environment to get matching
     * executable names. Falls back to an empty list on any error.
     *
     * Strategy: take the full PRoot launch command (which ends with `bash -l`),
     * strip the `-l` login flag, append `-c "compgen -c <prefix> | ..."` for a
     * one-shot non-interactive execution.
     */
    private fun runBashCompgen(prefix: String): List<String> {
        return try {
            val config   = LinuxSubsystem.getProotLaunchConfig(context)
            val bashCmd  = "compgen -c ${escapeShell(prefix)} 2>/dev/null | grep -v '^_' | sort -u | head -20"

            // Build one-shot command: replace terminal `bash -l` → `bash -c <cmd>`
            val cmdList = config.cmd.toMutableList()
            // Remove trailing `-l` login flag if present
            if (cmdList.lastOrNull() == "-l") cmdList.removeAt(cmdList.lastIndex)
            cmdList.add("-c")
            cmdList.add(bashCmd)

            // Build env map from envp array
            val envArr = config.envp

            val proc = ProcessBuilder(cmdList)
                .apply { environment().putAll(envArr.associate { e ->
                    val eq = e.indexOf('=')
                    if (eq > 0) e.substring(0, eq) to e.substring(eq + 1) else e to ""
                }) }
                .redirectErrorStream(true)
                .start()

            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            // Only accept safe command names (letters, digits, hyphens, dots)
            lines.filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.\\-]*")) }.take(20)
        } catch (e: Exception) {
            Log.w(TAG, "queryCompletions error: ${e.message}")
            emptyList()
        }
    }

    private fun escapeShell(s: String) = "'${s.replace("'", "'\\''")}'"

    // ── PTY output → WebView (called from IO thread) ───────────────────────

    fun sendOutputToJs(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("writeToTerminal('$b64')", null)
        }
    }

    /** Send a raw escape sequence directly to xterm.js for display */
    fun sendRawToJs(text: String) {
        sendOutputToJs(text.toByteArray(Charsets.UTF_8))
    }

    /** Programmatically send a key sequence to the PTY (from extra-key bar) */
    fun sendKeyToPty(sequence: String) {
        session.write(sequence.toByteArray(Charsets.UTF_8))
    }

    /** Send arbitrary bytes to the PTY (paste, etc.) */
    fun sendBytesToPty(bytes: ByteArray) {
        session.write(bytes)
    }

    /** Clear the terminal viewport */
    fun clear() {
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("clearTerminal()", null)
        }
    }

    /** Refresh terminal canvas layout and repaint character cells */
    fun refresh() {
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("window.refreshTerminal && window.refreshTerminal()", null)
        }
    }

    /** Refocus the WebView so soft keyboard stays active */
    fun refocus() {
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("refocusTerminal()", null)
        }
    }
}
