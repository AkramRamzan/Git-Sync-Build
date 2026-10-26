package com.aro.code.ui.editor

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class EditorEvent(val type: String, val payload: String, val filePath: String? = null)

/**
 * Module 4 – JavascriptInterface bridge between Monaco/CodeMirror WebView
 * and the Kotlin backend. Events are emitted as a SharedFlow.
 */
@Singleton
class EditorBridge @Inject constructor() {
    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EditorEvent> = _events

    @JavascriptInterface
    fun onContentChanged(filePath: String, content: String) {
        _events.tryEmit(EditorEvent("content_changed", content, filePath))
    }

    @JavascriptInterface
    fun onCursorMoved(line: Int, col: Int) {
        _events.tryEmit(EditorEvent("cursor", """{"line":$line,"col":$col}"""))
    }

    @JavascriptInterface
    fun onDiffRequested(filePath: String) {
        _events.tryEmit(EditorEvent("diff_request", filePath))
    }

    @JavascriptInterface
    fun onPromptSubmitted(prompt: String) {
        _events.tryEmit(EditorEvent("prompt", prompt))
    }
}
