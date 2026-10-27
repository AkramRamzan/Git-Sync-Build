package com.aro.code.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Module 4 – Native High-Performance Code Editor Screen.
 * Full-screen editor with line numbering gutter, horizontal & vertical scrolling,
 * multi-language syntax highlighting, programmer symbol row, font scaling,
 * VS Code dark aesthetic, and direct atomic saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    filePath: String,
    onBack: (() -> Unit)? = null,
    onAskAi: ((String) -> Unit)? = null,
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

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = content, selection = TextRange(content.length)))
    }

    LaunchedEffect(content) {
        if (textFieldValue.text != content) {
            val safeCursor = textFieldValue.selection.start.coerceIn(0, content.length)
            textFieldValue = TextFieldValue(text = content, selection = TextRange(safeCursor))
        }
    }

    var editorFontSize by remember { mutableStateOf(13) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    var showSearchBar by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var replaceWithText by remember { mutableStateOf("") }

    val matchCount = remember(content, searchText) {
        if (searchText.isBlank() || content.isEmpty()) 0
        else {
            var count = 0
            var idx = 0
            while (true) {
                val found = content.indexOf(searchText, idx)
                if (found == -1) break
                count++
                idx = found + searchText.length
            }
            count
        }
    }

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
                    // Undo
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = uiState.canUndo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (uiState.canUndo) MaterialTheme.colorScheme.primary else Color(0xFF555555)
                        )
                    }

                    // Redo
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = uiState.canRedo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (uiState.canRedo) MaterialTheme.colorScheme.primary else Color(0xFF555555)
                        )
                    }

                    // Find / Search toggle
                    IconButton(
                        onClick = { showSearchBar = !showSearchBar }
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Find & Replace",
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // Ask AI about this file
                    IconButton(
                        onClick = { onAskAi?.invoke(filePath) }
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = "Ask AI",
                            tint = Color(0xFFFFD54F)
                        )
                    }

                    // Save
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
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.statusMessage ?: "Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Font size decrease
                        Text(
                            text = "A-",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (editorFontSize > 10) Color(0xFF4FC3F7) else Color(0xFF555555),
                            modifier = Modifier
                                .clickable(enabled = editorFontSize > 10) { editorFontSize -= 1 }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "${editorFontSize}sp",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF858585),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        // Font size increase
                        Text(
                            text = "A+",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (editorFontSize < 24) Color(0xFF4FC3F7) else Color(0xFF555555),
                            modifier = Modifier
                                .clickable(enabled = editorFontSize < 24) { editorFontSize += 1 }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "L:$lineCount | C:${content.length}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF858585)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
        ) {
            // Find & Replace Bar
            if (showSearchBar) {
                Surface(
                    color = Color(0xFF252526),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = { Text("Find...", fontSize = 12.sp, color = Color(0xFF858585)) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (searchText.isBlank()) "" else "$matchCount match${if (matchCount == 1) "" else "es"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (matchCount > 0) Color(0xFF4FC3F7) else Color(0xFFE57373)
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { showSearchBar = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color(0xFF858585), modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = replaceWithText,
                                onValueChange = { replaceWithText = it },
                                placeholder = { Text("Replace...", fontSize = 12.sp, color = Color(0xFF858585)) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                            )
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = { viewModel.replaceText(searchText, replaceWithText, replaceAll = false) },
                                enabled = matchCount > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Replace", fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = { viewModel.replaceText(searchText, replaceWithText, replaceAll = true) },
                                enabled = matchCount > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("All", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Main Editor Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                                fontSize = editorFontSize.sp,
                                lineHeight = (editorFontSize * 1.5).sp,
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

                        // Code text editing area with syntax highlighting
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(horizontalScroll)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            BasicTextField(
                                value = textFieldValue,
                                onValueChange = { newTfv ->
                                    textFieldValue = newTfv
                                    viewModel.updateContent(newTfv.text)
                                },
                                visualTransformation = remember { CodeSyntaxVisualTransformation() },
                                textStyle = TextStyle(
                                    color = Color(0xFFD4D4D4),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = editorFontSize.sp,
                                    lineHeight = (editorFontSize * 1.5).sp
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

            // Quick Programmer Symbols Keybar (Termux / VS Code style)
            Surface(
                color = Color(0xFF252526),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val symbols = listOf(
                        "Tab" to "    ",
                        "{" to "{",
                        "}" to "}",
                        "(" to "(",
                        ")" to ")",
                        "[" to "[",
                        "]" to "]",
                        ";" to ";",
                        ":" to ":",
                        "=" to "=",
                        "\"" to "\"",
                        "'" to "'",
                        "->" to "->",
                        "=>" to "=>",
                        "<" to "<",
                        ">" to ">",
                        "/" to "/",
                        "\\" to "\\",
                        "|" to "|",
                        "&" to "&",
                        "!" to "!",
                        "?" to "?",
                        "_" to "_",
                        "+" to "+",
                        "-" to "-",
                        "*" to "*",
                        "%" to "%"
                    )
                    items(symbols) { (label, insertValue) ->
                        Surface(
                            onClick = {
                                val selStart = textFieldValue.selection.min
                                val selEnd = textFieldValue.selection.max
                                val currentText = textFieldValue.text
                                val safeStart = selStart.coerceIn(0, currentText.length)
                                val safeEnd = selEnd.coerceIn(0, currentText.length)
                                val newText = currentText.replaceRange(safeStart, safeEnd, insertValue)
                                val newCursor = safeStart + insertValue.length
                                textFieldValue = TextFieldValue(text = newText, selection = TextRange(newCursor))
                                viewModel.updateContent(newText)
                            },
                            color = Color(0xFF333333),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF444444)),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = Color(0xFFE0E0E0),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
