package com.aro.code.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aro.code.ai.AntiGravityModel
import com.aro.code.ai.DiffPreviewBlock
import com.aro.code.ai.GeminiModel
import com.aro.code.ui.auth.AntiGravityAccountDialog
import com.aro.code.ui.editor.DiffPreviewDialog

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
    var showModelMenu by remember { mutableStateOf(false) }
    var showAntiGravityModelMenu by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    // Folder/Tree picker — opens SAF document tree picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { uri ->
            val files = readFolderFiles(context, uri)
            if (files.isNotEmpty()) {
                viewModel.attachMultipleFiles(files)
            }
        }
    }

    LaunchedEffect(state.currentMessages.size, state.currentMessages.lastOrNull()?.text?.length, input.isNotEmpty()) {
        if (state.currentMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.currentMessages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (state.engine == ChatEngine.ANTIGRAVITY) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Anti-Gravity Agent",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = state.selectedAntiGravityModel.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Gemini AI Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showModelMenu = true }
                            ) {
                                Text(
                                    text = state.selectedModel.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch model",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }
                        ) {
                            GeminiModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                model.displayName,
                                                fontWeight = if (model == state.selectedModel) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                model.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.switchModel(model)
                                        showModelMenu = false
                                    }
                                )
                            }
                        }
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
                    if (state.engine == ChatEngine.ANTIGRAVITY) {
                        IconButton(onClick = { viewModel.openAuthDialog() }) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = "Anti-Gravity Account",
                                tint = if (state.isAntiGravitySignedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // New Session Thread
                    IconButton(onClick = { viewModel.startNewSession() }) {
                        Icon(Icons.Default.Add, contentDescription = "New Conversation Thread")
                    }

                    // Clear Current History
                    IconButton(onClick = { viewModel.clearSessionHistory() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Session History")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            // ── Primary Engine Selector (Anti-Gravity Agent vs Gemini Chat) ──
            TabRow(
                selectedTabIndex = if (state.engine == ChatEngine.ANTIGRAVITY) 0 else 1,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = state.engine == ChatEngine.ANTIGRAVITY,
                    onClick = { viewModel.selectEngine(ChatEngine.ANTIGRAVITY) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Anti-Gravity Agent", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                Tab(
                    selected = state.engine == ChatEngine.GEMINI,
                    onClick = { viewModel.selectEngine(ChatEngine.GEMINI) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Gemini Chat", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            // ── Termux CLI Header Box for Anti-Gravity ──
            if (state.engine == ChatEngine.ANTIGRAVITY) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "❯_ agy",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50),
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Termux CLI v2.0",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                            }

                            // Termux CLI Model selector
                            Box {
                                Surface(
                                    onClick = { showAntiGravityModelMenu = true },
                                    color = Color(0xFF2C2C2C),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            state.selectedAntiGravityModel.displayName,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Color(0xFF81D4FA)
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = Color(0xFF81D4FA),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showAntiGravityModelMenu,
                                    onDismissRequest = { showAntiGravityModelMenu = false }
                                ) {
                                    AntiGravityModel.entries.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        model.displayName,
                                                        fontWeight = if (model == state.selectedAntiGravityModel) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    Text(
                                                        model.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.switchAntiGravityModel(model)
                                                showAntiGravityModelMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.isAntiGravitySignedIn)
                                    "● auth: Google OAuth (ya29... connected)"
                                else
                                    "○ auth: Not signed in",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (state.isAntiGravitySignedIn) Color(0xFF81C784) else Color(0xFFE57373)
                            )
                            if (!state.isAntiGravitySignedIn) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "[Sign In]",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color(0xFF64B5F6),
                                    modifier = Modifier.clickable { viewModel.openAuthDialog() }
                                )
                            }
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Google AI Studio Mode · Model: ${state.selectedModel.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(
                            onClick = { showModelMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Switch Model", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // ── Active Project Context Badge & Pro Summary Mode ──
            Surface(
                color = if (state.activeProjectName != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (state.activeProjectName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.activeProjectName != null) "📁 Project: ${state.activeProjectName}" else "📁 No Project Selected",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    if (state.activeProjectName != null) {
                        TextButton(
                            onClick = { viewModel.generateProjectSummary() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            enabled = !state.isLoading
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Summary Mode (Pro)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // ── Step Execution Box (Termux CLI style) ──
            state.activeStep?.let { step ->
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = step,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFFA5D6A7)
                        )
                    }
                }
            }

            // ── Messages List (Completely Separated per Engine) ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Error banner
            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Attached files row
            if (state.attachedFiles.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(state.attachedFiles) { index, file ->
                        InputChip(
                            selected = true,
                            onClick = {},
                            label = { Text(file.name, maxLines = 1) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.removeAttachment(index) },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove")
                                }
                            }
                        )
                    }
                }
            }

            // Input area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach button with dropdown menu (File / Multiple Files / Folder)
                Box {
                    IconButton(onClick = { showAttachMenu = true }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false }
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            text = { Text("Attach Single File") },
                            onClick = {
                                showAttachMenu = false
                                filePickerLauncher.launch("*/*")
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            text = { Text("Attach Multiple Files") },
                            onClick = {
                                showAttachMenu = false
                                multiFilePickerLauncher.launch("*/*")
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            text = { Text("Attach Folder (All Files)") },
                            onClick = {
                                showAttachMenu = false
                                folderPickerLauncher.launch(null)
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            if (state.engine == ChatEngine.ANTIGRAVITY)
                                "Instruct Anti-Gravity agent (fix bug, edit file)..."
                            else
                                "Ask Gemini a question or explain code..."
                        )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )

                Spacer(Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        val textToSend = input
                        input = ""
                        viewModel.send(textToSend)
                    },
                    enabled = (input.isNotBlank() || state.attachedFiles.isNotEmpty()) && !state.isLoading
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
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
private fun ChatBubble(
    message: ChatMessage,
    isAntiGravity: Boolean,
    onViewDiff: (DiffPreviewBlock) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (isAntiGravity) {
                Color(0xFF1E1E1E)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Header badge for Anti-Gravity
                if (!isUser && isAntiGravity) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            "⚡ agy agent",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                // Attached file tags on user messages
                if (message.attachments.isNotEmpty()) {
                    message.attachments.forEach { file ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = "📎 ${file.name}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = if (message.isStreaming && message.text.isEmpty()) "..." else message.text,
                    fontFamily = if (isAntiGravity) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (isAntiGravity) 12.sp else 14.sp,
                    color = if (!isUser && isAntiGravity) Color(0xFFE0E0E0) else Color.Unspecified,
                    style = MaterialTheme.typography.bodyMedium
                )

                // If diffs are available in the response, show clickable button
                if (message.diffBlocks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    message.diffBlocks.forEach { diff ->
                        Button(
                            onClick = { onViewDiff(diff) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAntiGravity) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Review & Apply Diff (${diff.filePath.substringAfterLast('/')})",
                                style = MaterialTheme.typography.labelMedium
                            )
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
