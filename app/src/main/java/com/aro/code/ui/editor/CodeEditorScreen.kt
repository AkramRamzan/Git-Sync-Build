package com.aro.code.ui.editor

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Module 4 – Hybrid editor screen.
 * Jetpack Compose wrapper around the Monaco/CodeMirror WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    filePath: String,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top toolbar
        TopAppBar(
            title = { Text(filePath.substringAfterLast('/')) },
            actions = {
                TextButton(onClick = { viewModel.saveFile() }) { Text("Save") }
                TextButton(onClick = { viewModel.requestDiff(filePath) }) { Text("Diff") }
            }
        )

        // Monaco/CodeMirror WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    addJavascriptInterface(viewModel.bridge, "AroCode")
                    loadUrl("file:///android_asset/editor/index.html")
                }
            },
            update = { webView ->
                // Push content into editor when state changes
                uiState.currentContent?.let { content ->
                    val escaped = content
                        .replace("\\", "\\\\")
                        .replace("`", "\\`")
                    webView.evaluateJavascript(
                        "window.aroSetContent(`$escaped`, `$filePath`);", null
                    )
                }
            },
            modifier = Modifier.weight(1f)
        )

        // Status bar
        if (uiState.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        uiState.statusMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}
