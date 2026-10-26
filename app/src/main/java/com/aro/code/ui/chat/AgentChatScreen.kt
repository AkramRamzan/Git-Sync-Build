package com.aro.code.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aro.code.ai.AntiGravityModel
import com.aro.code.ai.DiffPreviewBlock
import com.aro.code.ai.GeminiModel
import com.aro.code.ui.auth.AntiGravityAccountDialog
import com.aro.code.ui.editor.DiffPreviewDialog
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    initialEngine: ChatEngine = ChatEngine.ANTIGRAVITY,
    onBack: (() -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showModelSelectorMenu by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current

    LaunchedEffect(initialEngine) {
        viewModel.selectEngine(initialEngine)
    }

    // File picker launcher — single file
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = queryFileName(context, it) ?: "attached_file.txt"
            val content = context.contentResolver.openInputStream(it)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
            viewModel.attachFile(name = fileName, content = content, uriString = it.toString())
        }
    }

    // Multiple files picker — select several files at once
    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val attachedFiles = uris.mapNotNull { uri ->
            val fileName = queryFileName(context, uri) ?: "file_${System.currentTimeMillis()}.txt"
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: ""
            }.getOrDefault("")
            if (content.isNotBlank()) AttachedFile(name = fileName, content = content, uriString = uri.toString()) else null
        }
        if (attachedFiles.isNotEmpty()) {
            viewModel.attachMultipleFiles(attachedFiles)
        }
    }

    // Attach Folder picker — reads all files in folder and attaches them
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { uri ->
            viewModel.selectActiveProject(uri)
            val files = readFolderFiles(context, uri)
            if (files.isNotEmpty()) {
                viewModel.attachMultipleFiles(files)
            }
        }
    }

    // Project folder selector launcher — sets active project directory context
    val projectPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { uri ->
            viewModel.selectActiveProject(uri)
        }
    }

    // Auto-scroll when messages update or streaming text changes
    LaunchedEffect(state.currentMessages.size, state.currentMessages.lastOrNull()?.text?.length) {
        if (state.currentMessages.isNotEmpty()) {
            listState.scrollToItem(state.currentMessages.lastIndex)
        }
    }

    // Auto-scroll when keyboard opens so conversation is never hidden
    val imeInsets = WindowInsets.ime
    val isKeyboardOpen = imeInsets.getBottom(density) > 0
    LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen && state.currentMessages.isNotEmpty()) {
            delay(120)
            listState.animateScrollToItem(state.currentMessages.lastIndex)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (state.engine == ChatEngine.ANTIGRAVITY) Icons.Default.Bolt else Icons.Default.Chat,
                            contentDescription = null,
                            tint = if (state.engine == ChatEngine.ANTIGRAVITY) Color(0xFF58A6FF) else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.engine == ChatEngine.ANTIGRAVITY) "ARO Code AI Agent" else "Gemini AI Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    // New Session Thread (+)
                    IconButton(
                        onClick = { viewModel.startNewSession() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Conversation Thread", modifier = Modifier.size(20.dp))
                    }

                    // Clear Current History (Trash)
                    IconButton(
                        onClick = { viewModel.clearSessionHistory() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Session History", modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .navigationBarsPadding()
        ) {
            // Compact engine selector tabs
            TabRow(
                selectedTabIndex = if (state.engine == ChatEngine.ANTIGRAVITY) 0 else 1,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(34.dp)
            ) {
                Tab(
                    selected = state.engine == ChatEngine.ANTIGRAVITY,
                    onClick = { viewModel.selectEngine(ChatEngine.ANTIGRAVITY) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Anti-Gravity Agent", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                Tab(
                    selected = state.engine == ChatEngine.GEMINI,
                    onClick = { viewModel.selectEngine(ChatEngine.GEMINI) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Gemini Chat", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            // Step Execution Box (Termux CLI style)
            state.activeStep?.let { step ->
                Surface(
                    color = Color(0xFF161B22),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = step,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = Color(0xFFA5D6A7),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── Messages List (Dense Termux Flow, Fills Viewing Area) ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.currentMessages) { msg ->
                    ChatBubble(
                        message = msg,
                        isAntiGravity = state.engine == ChatEngine.ANTIGRAVITY,
                        onViewDiff = { diff -> viewModel.showDiffPreview(diff) }
                    )
                }
            }

            if (state.isLoading && state.activeStep == null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            // Error banner
            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }

            // Attached files row (if any)
            if (state.attachedFiles.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(state.attachedFiles) { index, file ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF1F6FEB).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFF388BFD))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📎 ${file.name}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color(0xFF58A6FF),
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = Color(0xFF8B949E),
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable { viewModel.removeAttachment(index) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Action Buttons Row (Positioned directly above the chat input box) ──
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    ActionChip(
                        icon = Icons.Default.Bolt,
                        label = "⚡ Architecture",
                        enabled = !state.isLoading,
                        onClick = {
                            viewModel.send("Explain the overall architecture, directory structure, and main flow of this project.")
                        }
                    )
                }
                item {
                    ActionChip(
                        icon = Icons.Default.Code,
                        label = "🔍 Find Bugs",
                        enabled = !state.isLoading,
                        onClick = {
                            viewModel.send("Analyze the active project workspace for potential bugs, memory leaks, performance bottlenecks, and code smells.")
                        }
                    )
                }
                item {
                    ActionChip(
                        icon = Icons.Default.Bolt,
                        label = "🛠️ Git Status",
                        enabled = !state.isLoading,
                        onClick = {
                            viewModel.send("Check git status and summarize recent changes and modified files in this repository.")
                        }
                    )
                }
                item {
                    ActionChip(
                        icon = Icons.Default.Bolt,
                        label = "🚀 Optimize",
                        enabled = !state.isLoading,
                        onClick = {
                            viewModel.send("Suggest high-impact performance optimizations and clean architecture refactoring for this project.")
                        }
                    )
                }
                if (state.activeProjectName != null) {
                    item {
                        ActionChip(
                            icon = Icons.Default.Code,
                            label = "📋 Summary",
                            enabled = !state.isLoading,
                            isHighlight = true,
                            onClick = { viewModel.generateProjectSummary() }
                        )
                    }
                }
            }

            // Blue attachment/input alignment line (directly between action buttons and input box)
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                thickness = 1.dp,
                color = Color(0xFF1F6FEB).copy(alpha = 0.5f)
            )

            // ── Compact Chat Input Box (Flush against virtual keyboard, zero wasted space) ──
            Surface(
                color = Color(0xFF161B22),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF30363D)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Neatly positioned Attachment Button
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach",
                                tint = Color(0xFF8B949E),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                text = { Text("Attach Single File", fontSize = 12.sp) },
                                onClick = {
                                    showAttachMenu = false
                                    filePickerLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                text = { Text("Attach Multiple Files", fontSize = 12.sp) },
                                onClick = {
                                    showAttachMenu = false
                                    multiFilePickerLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                text = { Text("Attach Folder (All Files)", fontSize = 12.sp) },
                                onClick = {
                                    showAttachMenu = false
                                    folderPickerLauncher.launch(null)
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFF30363D))
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                        tint = Color(0xFF58A6FF)
                                    )
                                },
                                text = {
                                    Column {
                                        Text(
                                            "Select Project Folder",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF58A6FF)
                                        )
                                        state.activeProjectName?.let { proj ->
                                            Text(
                                                "Active: $proj",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = Color(0xFF8B949E)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    showAttachMenu = false
                                    projectPickerLauncher.launch(null)
                                }
                            )
                        }
                    }

                    // Small Model Selection Button positioned next to the attachment button
                    Box {
                        IconButton(
                            onClick = { showModelSelectorMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Select Model",
                                tint = Color(0xFF58A6FF),
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showModelSelectorMenu,
                            onDismissRequest = { showModelSelectorMenu = false }
                        ) {
                            DropdownMenuItem(
                                enabled = false,
                                onClick = {},
                                text = {
                                    Text(
                                        "Select AI Model",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF8B949E)
                                    )
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = Color(0xFF30363D))
                            AntiGravityModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            when (model) {
                                                AntiGravityModel.FLASH_3_8 -> Icons.Default.Bolt
                                                AntiGravityModel.CLAUDE_SONNET_4_6,
                                                AntiGravityModel.CLAUDE_OPUS_4_6 -> Icons.Default.Code
                                                AntiGravityModel.GPT_OSS_120B -> Icons.Default.SmartToy
                                                AntiGravityModel.GEMINI_4_ARGON -> Icons.Default.AutoAwesome
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (model == state.selectedAntiGravityModel) Color(0xFF58A6FF) else Color(0xFF8B949E)
                                        )
                                    },
                                    text = {
                                        Column {
                                            Text(
                                                when (model) {
                                                    AntiGravityModel.FLASH_3_8 -> "Gemini 3.8 Flash"
                                                    AntiGravityModel.CLAUDE_SONNET_4_6 -> "Claude Sonnet 4.6 Thinking"
                                                    AntiGravityModel.CLAUDE_OPUS_4_6 -> "Claude Opus 4.6 Thinking"
                                                    AntiGravityModel.GPT_OSS_120B -> "GPT-O-S-S 120B Medium"
                                                    AntiGravityModel.GEMINI_4_ARGON -> "Gemini 4 Argon"
                                                },
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = if (model == state.selectedAntiGravityModel) FontWeight.Bold else FontWeight.Normal,
                                                color = if (model == state.selectedAntiGravityModel) Color(0xFF58A6FF) else Color.Unspecified
                                            )
                                            Text(
                                                model.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.switchAntiGravityModel(model)
                                        showModelSelectorMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Compact Text Input Box
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFE6EDF3)
                        ),
                        cursorBrush = SolidColor(Color(0xFF58A6FF)),
                        maxLines = 4,
                        decorationBox = { innerTextField ->
                            if (input.isEmpty()) {
                                Text(
                                    text = if (state.engine == ChatEngine.ANTIGRAVITY)
                                        "Instruct Anti-Gravity agent (fix bug, edit code)..."
                                    else
                                        "Ask Gemini a question or explain code...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF6E7681)
                                )
                            }
                            innerTextField()
                        }
                    )

                    // Neatly positioned Send Button
                    val canSend = (input.isNotBlank() || state.attachedFiles.isNotEmpty()) && !state.isLoading
                    IconButton(
                        onClick = {
                            val textToSend = input
                            input = ""
                            viewModel.send(textToSend)
                        },
                        enabled = canSend,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) Color(0xFF58A6FF) else Color(0xFF484F58),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // Interactive Diff Preview Dialog
    state.activeDiffForPreview?.let { diff ->
        DiffPreviewDialog(
            diff = diff,
            onApply = { viewModel.applyDiff(it) },
            onDismiss = { viewModel.dismissDiffPreview() }
        )
    }

    // Anti-Gravity Google Account Dialog
    if (state.showAuthDialog) {
        AntiGravityAccountDialog(
            isSignedIn = state.isAntiGravitySignedIn,
            userTokenSnippet = state.antiGravityTokenSnippet,
            onDismiss = { viewModel.dismissAuthDialog() },
            onSubmitCode = { code, verifier, cid, onResult ->
                viewModel.submitGoogleAuthCode(code, verifier, cid, onResult)
            },
            onSignOut = { viewModel.signOutGoogle() }
        )
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    isHighlight: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (isHighlight) Color(0xFF1F6FEB).copy(alpha = 0.25f) else Color(0xFF21262D),
        border = BorderStroke(1.dp, if (isHighlight) Color(0xFF58A6FF) else Color(0xFF30363D))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = if (isHighlight) Color(0xFF58A6FF) else Color(0xFF8B949E)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isHighlight) Color(0xFF58A6FF) else Color(0xFFC9D1D9)
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isAntiGravity: Boolean,
    onViewDiff: (DiffPreviewBlock) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                Color(0xFF0D47A1) // Compact deep blue for user
            } else if (isAntiGravity) {
                Color(0xFF161B22) // Termux-like dark console
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            border = if (!isUser && isAntiGravity) BorderStroke(1.dp, Color(0xFF30363D)) else null,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                // Header badge for Anti-Gravity
                if (!isUser && isAntiGravity) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 3.dp)
                    ) {
                        Text(
                            "❯_ agy agent",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                // Attached file tags on user messages
                if (message.attachments.isNotEmpty()) {
                    message.attachments.forEach { file ->
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFF1F6FEB).copy(alpha = 0.3f),
                            modifier = Modifier.padding(bottom = 3.dp)
                        ) {
                            Text(
                                text = "📎 ${file.name}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFF58A6FF),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Dense, compact conversation text (Termux formatting)
                Text(
                    text = if (message.isStreaming && message.text.isEmpty()) "..." else message.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = if (!isUser && isAntiGravity) Color(0xFFE6EDF3) else Color.White
                )

                // If diffs are available in the response, show clickable button
                if (message.diffBlocks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    message.diffBlocks.forEach { diff ->
                        Surface(
                            onClick = { onViewDiff(diff) },
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF238636),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Apply Diff: ${diff.filePath.substringAfterLast('/')}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}

/**
 * Reads all readable text files from a SAF-selected folder tree (recursively, max 50 files, 200KB each).
 */
private fun readFolderFiles(context: Context, treeUri: Uri): List<AttachedFile> {
    val result = mutableListOf<AttachedFile>()
    val docId = try {
        android.provider.DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        return result
    }

    val textExtensions = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml",
        "md", "txt", "gradle", "kts", "toml", "yaml", "yml", "html",
        "css", "sh", "c", "cpp", "h", "swift", "go", "rs", "dart"
    )

    fun traverseDir(parentDocId: String) {
        if (result.size >= 50) return
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 50) {
                val childDocId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2) ?: continue
                val size = cursor.getLong(3)

                if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Skip hidden folders and common non-code dirs
                    if (!name.startsWith(".") && name !in setOf("build", ".gradle", "node_modules", "__pycache__", ".git")) {
                        traverseDir(childDocId)
                    }
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in textExtensions && size in 1..200_000) {
                        runCatching {
                            val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                            val content = context.contentResolver.openInputStream(fileUri)?.use { stream ->
                                stream.bufferedReader(Charsets.UTF_8).readText()
                            } ?: return@runCatching
                            if (content.isNotBlank()) {
                                result.add(AttachedFile(name = name, content = content, uriString = fileUri.toString()))
                            }
                        }
                    }
                }
            }
        }
    }

    traverseDir(docId)
    return result
}
