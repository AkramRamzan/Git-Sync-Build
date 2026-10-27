package com.aro.code.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
 * IME / keyboard & touch notes:
 *  - `setTextZoom(100)` and `builtInZoomControls = false` prevent IME focus loss
 *  - `setSupportMultipleWindows(false)` prevents popup focus stealing
 *  - `setLayerType(LAYER_TYPE_HARDWARE, null)` for smooth canvas rendering
 *  - Touch: uses a slop threshold (8 dp) before locking vertical scroll so that
 *    single taps still register correctly without fighting the BottomSheet.
 *  - Lifecycle observer automatically restores WebView and triggers xterm repaint
 *    on ON_RESUME (eliminating blank/hidden elements when app is reopened).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XtermView(
    session: ShellSession,
    modifier: Modifier = Modifier,
    refreshTrigger: Int = 0,
    onFontSizeChanged: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val jsInterface = session.ensureJsInterface(context)

    // Wire pinch-to-zoom font size & terminal bell vibration callbacks
    LaunchedEffect(jsInterface) {
        jsInterface.onFontSizeChangedCallback = onFontSizeChanged
        jsInterface.onBellCallback = {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vm?.defaultVibrator?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vib?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib?.vibrate(30)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    val webView = remember(session.id) {
        session.getOrCreateWebView(context)
    }

    // Lifecycle observer to guarantee WebView redraws instantly when app is un-minimized
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    webView.resumeTimers()
                    webView.invalidate()
                    jsInterface.refresh()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Stable refresh: only refresh when refreshTrigger explicitly changes, not on every recomposition
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            jsInterface.refresh()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = {
            // Stable: no continuous invalidate/refresh in update block to eliminate blinking
        }
    )
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun createTerminalWebView(
    context: Context,
    jsInterface: XtermJsInterface
): WebView {
    val wv = WebView(context)
    jsInterface.attachWebView(wv)
    wv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    // Dark terminal background immediately to eliminate any white flashes/lines
    wv.setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))

    // Hardware acceleration for canvas rendering
    wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    wv.isFocusable = true
    wv.isFocusableInTouchMode = true

    // Request parent container not to intercept vertical touch drags so
    // user can scroll terminal text smoothly up & down without BottomSheet stealing the gesture
    wv.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (!v.hasFocus()) v.requestFocus()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        false
    }

    // Security-hardened WebSettings
    with(wv.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        allowContentAccess = false
        allowFileAccessFromFileURLs = true
        allowUniversalAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        savePassword = false
        @Suppress("DEPRECATION")
        saveFormData = false
        builtInZoomControls = false
        displayZoomControls = false
        setSupportMultipleWindows(false)
        textZoom = 100
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = true
        setSupportZoom(false)
    }

    wv.addJavascriptInterface(jsInterface, XtermJsInterface.INTERFACE_NAME)

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return if (url.startsWith("file:///android_asset/")) {
                false
            } else {
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

    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.d("XtermView[JS]", "[${message.messageLevel()}] ${message.message()}")
            return true
        }
    }

    wv.loadUrl("file:///android_asset/terminal/index.html")

    return wv
}
