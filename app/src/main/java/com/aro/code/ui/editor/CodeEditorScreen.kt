package com.aro.code.ui.editor

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import java.net.URLDecoder

/**
 * Module 4 – Hybrid editor screen.
 * Jetpack Compose wrapper around the Monaco/CodeMirror WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    filePath: String,
    onBack: (() -> Unit)? = null,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(filePath) {
        if (filePath.isNotBlank()) {
            val decodedPath = runCatching { URLDecoder.decode(filePath, "UTF-8") }.getOrDefault(filePath)
            viewModel.loadFile(decodedPath)
        }
    }

    val displayTitle = remember(filePath) {
        val decoded = runCatching { URLDecoder.decode(filePath, "UTF-8") }.getOrDefault(filePath)
        decoded.substringAfterLast('/').substringAfterLast(':')
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top toolbar
        TopAppBar(
            title = { Text(displayTitle, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                IconButton(onClick = { viewModel.saveFile() }) {
                    Icon(Icons.Default.Save, contentDescription = "Save File")
                }
                TextButton(onClick = { viewModel.requestDiff(filePath) }) {
                    Text("Diff")
                }
            }
        )

        // Monaco/CodeMirror WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(viewModel.bridge, "AroCode")
                    loadUrl("file:///android_asset/editor/index.html")
                }
            },
            update = { webView ->
                uiState.currentContent?.let { content ->
                    val escaped = content
                        .replace("\\", "\\\\")
                        .replace("`", "\\`")
                        .replace("$", "\\$")
                    webView.evaluateJavascript(
                        "window.aroSetContent(`$escaped`, `$displayTitle`);", null
                    )
                }
            },
            modifier = Modifier.weight(1f)
        )

        // Status bar
        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        uiState.statusMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}
