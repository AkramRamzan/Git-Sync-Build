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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Security: this bridge is added only to the single terminal WebView and the
 * WebView loads only bundled assets (no navigation allowed). Shell access is
 * already restricted to the PRoot container.
 */
class XtermJsInterface(
    private val context: Context,
    val session: PtySession,
    private val onReadyCalled: (rows: Int, cols: Int) -> Unit = { _, _ -> },
    private val onExitCalled:  (code: Int) -> Unit = {}
) {
    companion object {
        const val INTERFACE_NAME = "AroTerminal"
        private const val TAG = "XtermJsInterface"
    }

    var webView: WebView? = null
    var onModifierConsumedCallback: (String) -> Unit = {}
    var onFontSizeChangedCallback: (Float) -> Unit = {}
    var onBellCallback: () -> Unit = {}
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

    /** Cancel pending JS dispatch coroutines and clear WebView ref. Call when session is destroyed. */
    fun destroy() {
        scope.cancel()
        webView = null
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

    // ── Called by JS: user typed a key that consumed sticky modifier ───────

    @JavascriptInterface
    fun onModifierConsumed(modifier: String) {
        scope.launch(Dispatchers.Main) {
            onModifierConsumedCallback(modifier)
        }
    }

    // ── Called by JS: pinch-to-zoom changed font size ──────────────────────

    @JavascriptInterface
    fun onFontSizeChanged(size: Float) {
        scope.launch(Dispatchers.Main) {
            onFontSizeChangedCallback(size)
        }
    }

    // ── Called by JS: user selected text (Termux copy-on-select) ───────────

    @JavascriptInterface
    fun onTextSelected(base64: String) {
        try {
            val text = String(Base64.decode(base64, Base64.NO_WRAP), Charsets.UTF_8)
            if (text.isNotBlank()) {
                scope.launch(Dispatchers.Main) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("terminal", text)
                    cm?.setPrimaryClip(clip)
                }
            }
        } catch (_: Throwable) {}
    }

    // ── Called by JS: terminal bell \x07 (audio/haptic notification) ───────

    @JavascriptInterface
    fun onBell() {
        scope.launch(Dispatchers.Main) {
            onBellCallback()
        }
    }

    // ── Called by JS: user tapped a link ───────────────────────────────────

    @JavascriptInterface
    fun openUrl(url: String) {
        try {
            val parsed = Uri.parse(url.trim())
            // Only allow http:// and https:// — block file://, intent://, javascript: etc.
            val scheme = parsed.scheme?.lowercase() ?: ""
            if (scheme != "http" && scheme != "https") {
                Log.w(TAG, "openUrl blocked non-http scheme: $scheme for url=$url")
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openUrl failed: $url — ${e.message}")
        }
    }

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

    /** Update active CTRL and ALT modifier flags in xterm.js */
    fun setModifiers(ctrl: Boolean, alt: Boolean) {
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("window.setModifiers && window.setModifiers($ctrl, $alt);", null)
        }
    }

    /** Set dynamic terminal font size in xterm.js */
    fun setFontSize(size: Float) {
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("window.setFontSize && window.setFontSize($size);", null)
        }
    }

    /** Refocus the WebView so soft keyboard stays active */
    fun refocus() {
        scope.launch(Dispatchers.Main) {
            webView?.evaluateJavascript("refocusTerminal()", null)
        }
    }
}
