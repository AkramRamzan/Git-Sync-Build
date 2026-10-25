package com.aro.code.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup.LayoutParams
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * XtermView — Compose wrapper for an xterm.js terminal running in a WebView.
 *
 * Security hardening:
 *  - WebView loads only from `file:///android_asset/terminal/` (no external URLs)
 *  - `setAllowFileAccessFromFileURLs` is false → no cross-origin local file reads
 *  - Navigation to external origins is blocked; links open via the system browser
 *    through [XtermJsInterface.openUrl]
 *  - JavaScript is enabled only for the local asset page (no remote scripts)
 *  - No mixed content, no geolocation, no camera, no DOM storage external leakage
 *
 * IME / keyboard notes:
 *  - `setTextZoom(100)` and `builtInZoomControls = false` prevent IME focus loss
 *  - `setSupportMultipleWindows(false)` prevents popup focus stealing
 *  - `setLayerType(LAYER_TYPE_HARDWARE, null)` for smooth canvas rendering
 *
 * @param session        The [PtySession] to connect.
 * @param jsInterface    The [XtermJsInterface] that bridges JS ↔ PTY.
 * @param modifier       Compose modifier for sizing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XtermView(
    session: PtySession,
    jsInterface: XtermJsInterface,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val webView = remember(session.id) {
        createTerminalWebView(context, jsInterface)
    }

    DisposableEffect(session.id) {
        onDispose {
            // Don't destroy — session owner manages lifecycle
            // WebView stays alive as long as the session is alive
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = { /* no-op; updates go through JavascriptInterface */ }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createTerminalWebView(
    context: Context,
    jsInterface: XtermJsInterface
): WebView {
    val wv = WebView(context)
    jsInterface.attachWebView(wv)
    wv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    // ── Hardware acceleration for canvas rendering ──
    wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

    // ── Security-hardened WebSettings ──
    with(wv.settings) {
        javaScriptEnabled = true                     // required for xterm.js
        domStorageEnabled = false                    // not needed
        allowFileAccess = true                       // load assets
        allowContentAccess = false
        allowFileAccessFromFileURLs = false          // no cross-origin local reads
        allowUniversalAccessFromFileURLs = false     // no external fetching
        @Suppress("DEPRECATION")
        savePassword = false
        @Suppress("DEPRECATION")
        saveFormData = false
        builtInZoomControls = false                  // prevent zoom stealing focus
        displayZoomControls = false
        setSupportMultipleWindows(false)
        textZoom = 100                               // prevent IME-triggered zoom
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = true
        setSupportZoom(false)
    }

    // ── JavascriptInterface — no port, no network ──
    wv.addJavascriptInterface(jsInterface, XtermJsInterface.INTERFACE_NAME)

    // ── Block external navigation; open links in system browser ──
    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return if (url.startsWith("file:///android_asset/")) {
                false // allow loading from assets
            } else {
                // Delegate to AroTerminal.openUrl (JavascriptInterface) — already done in JS
                // Fallback: open in system browser
                jsInterface.openUrl(url)
                true
            }
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return if (url.startsWith("file:///android_asset/")) {
                false
            } else {
                jsInterface.openUrl(url)
                true
            }
        }
    }

    // ── Minimal ChromeClient (no popups, no alerts exposed) ──
    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.d("XtermView[JS]", "[${message.messageLevel()}] ${message.message()}")
            return true
        }
    }

    // ── Load bundled xterm.js page ──
    wv.loadUrl("file:///android_asset/terminal/index.html")

    return wv
}
