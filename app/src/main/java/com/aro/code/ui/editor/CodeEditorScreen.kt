package com.aro.code.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Module 4 – Native High-Performance Code Editor Screen.
 * Full-screen editor with line numbering gutter, horizontal & vertical scrolling,
 * VS Code dark aesthetic, and direct atomic saving.
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
            viewModel.loadFile(filePath)
        }
    }

    val displayTitle = remember(filePath) {
        filePath.substringAfterLast('/').substringAfterLast(':')
    }

    val content = uiState.currentContent ?: ""
    val lineCount = remember(content) {
        if (content.isEmpty()) 1 else content.count { it == '\n' } + 1
    }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val dirtySuffix = if (uiState.isDirty) " *" else ""
                        Text(
                            text = "${displayTitle.ifBlank { "Untitled" }}$dirtySuffix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (uiState.isDirty) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                        Text(
                            text = filePath.takeLast(40),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveFile() },
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save File",
                            tint = if (uiState.isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF181818),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.statusMessage ?: "Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Lines: $lineCount | Characters: ${content.length}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF858585)
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
        ) {
            if (uiState.isLoading && uiState.currentContent == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Loading file...",
                        color = Color(0xFFD4D4D4),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                ) {
                    // Line numbers gutter
                    Column(
                        modifier = Modifier
                            .background(Color(0xFF252526))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        val gutterText = remember(lineCount) {
                            (1..lineCount).joinToString("\n")
                        }
                        Text(
                            text = gutterText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = Color(0xFF6E7681),
                            modifier = Modifier.widthIn(min = 28.dp)
                        )
                    }

                    // Vertical separator
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF333333))
                    )

                    // Code text editing area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { newText ->
                                viewModel.updateContent(newText)
                            },
                            textStyle = TextStyle(
                                color = Color(0xFFD4D4D4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(Color(0xFF4FC3F7)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 400.dp)
                        )
                    }
                }
            }
        }
    }
}
