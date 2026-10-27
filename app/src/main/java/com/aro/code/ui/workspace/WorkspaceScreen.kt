package com.aro.code.ui.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aro.code.filesystem.ProjectNode
import com.aro.code.terminal.BridgeStatusPill
import com.aro.code.terminal.TerminalBottomSheet
import com.aro.code.terminal.TerminalViewModel
import com.aro.code.terminal.TermuxBridgeService
import com.aro.code.ui.auth.AntiGravityAccountDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onFileOpen: (String) -> Unit,
    onChatOpen: (engine: String) -> Unit,
    onSettingsOpen: () -> Unit,
    viewModel: WorkspaceViewModel = hiltViewModel(),
    terminalViewModel: TerminalViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // Terminal bottom sheet state
    var showTerminal by remember { mutableStateOf(false) }
    val bridgeStatus by TermuxBridgeService.state.status.collectAsState()

    // SAF directory picker launcher
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.onProjectFolderSelected(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.Menu, contentDescription = "Explorer (Open Folder)")
                    }
                },
                title = {
                    Column {
                        Text("Aro Code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = state.projectName ?: "Workspace",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Open SAF Folder
                    IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Project Folder")
                    }

                    // Git Source Control
                    IconButton(onClick = { viewModel.openGitDialog() }) {
                        Icon(Icons.Default.Code, contentDescription = "Git Source Control")
                    }

                    // Anti-Gravity Autonomous Agent (⚡)
                    IconButton(onClick = { onChatOpen("antigravity") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = "Anti-Gravity Autonomous Agent",
                            tint = if (state.isGoogleSignedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Gemini AI Chat (💬)
                    IconButton(onClick = { onChatOpen("gemini") }) {
                        Icon(Icons.Default.Chat, contentDescription = "Gemini AI Chat")
                    }

                    // Cloud Build Trigger
                    IconButton(onClick = { viewModel.triggerCloudBuild() }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Cloud Build")
                    }

                    // Settings Button (⚙)
                    IconButton(onClick = onSettingsOpen) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }

                    // Profile Icon (👤) — Anti-Gravity Account & Logout Trigger
                    IconButton(onClick = { viewModel.openAntiGravityDialog() }) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Profile & Account",
                            tint = if (state.isGoogleSignedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 🟢/🔴 Bridge Status Pill — tap to open Terminal
                BridgeStatusPill(
                    status = bridgeStatus,
                    onClick = { showTerminal = true }
                )
                FloatingActionButton(
                    onClick = { directoryPickerLauncher.launch(null) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Select Project Folder")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Project header bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.projectUriString != null) Icons.Default.FolderSpecial else Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.projectName ?: "Internal Workspace",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.openCreateFileDialog() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "New File", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { viewModel.openCreateFolderDialog() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", modifier = Modifier.size(16.dp))
                        }
                        TextButton(onClick = { viewModel.openGitDialog() }) {
                            Text("Git", style = MaterialTheme.typography.labelSmall)
                        }
                        if (state.projectUriString != null) {
                            TextButton(onClick = { viewModel.resetToInternalStorage() }) {
                                Text("Reset", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = { viewModel.clonePrompt() }) {
                            Text("Clone", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Quick File Filter Search Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Filter files (e.g. .kt, Activity)...", fontSize = 12.sp, color = Color(0xFF858585)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                )
            }

            state.statusMessage?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Hierarchical File Tree
            if (state.projectNodes.isEmpty() && !state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (state.searchQuery.isNotBlank()) "No files match '${state.searchQuery}'" else "No files found in workspace.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { directoryPickerLauncher.launch(null) }) {
                            Text("Open Project Directory")
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.projectNodes, key = { it.path }) { node ->
                        ProjectNodeRow(
                            node = node,
                            onToggle = { viewModel.toggleDirectory(node) },
                            onFileClick = {
                                val openPath = node.uri?.toString() ?: node.file?.absolutePath ?: node.path
                                onFileOpen(openPath)
                            },
                            onDelete = { viewModel.deleteNode(node) }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            // Clone Dialog
            if (state.showCloneDialog) {
                CloneDialog(
                    onConfirm = { url -> viewModel.cloneRepo(url) },
                    onDismiss = { viewModel.dismissCloneDialog() }
                )
            }

            // Git Source Control Dialog
            if (state.showGitDialog) {
                GitDialog(
                    gitStatus = state.gitStatus,
                    onCommitAndPush = { msg -> viewModel.commitAndPush(msg) },
                    onPull = { viewModel.gitPull() },
                    onCloneClick = {
                        viewModel.dismissGitDialog()
                        viewModel.clonePrompt()
                    },
                    onDismiss = { viewModel.dismissGitDialog() }
                )
            }

            // Create File Dialog
            if (state.showCreateFileDialog) {
                InputDialog(
                    title = "Create New File",
                    label = "File Name (e.g. MainActivity.kt)",
                    confirmText = "Create",
                    onConfirm = { name -> viewModel.createFile(name) },
                    onDismiss = { viewModel.dismissCreateFileDialog() }
                )
            }

            // Create Folder Dialog
            if (state.showCreateFolderDialog) {
                InputDialog(
                    title = "Create New Folder",
                    label = "Folder Name",
                    confirmText = "Create",
                    onConfirm = { name -> viewModel.createFolder(name) },
                    onDismiss = { viewModel.dismissCreateFolderDialog() }
                )
            }

            // Anti-Gravity Google Account Dialog (Link generation + Code Input + Logout)
            if (state.showAntiGravityDialog) {
                AntiGravityAccountDialog(
                    isSignedIn = state.isGoogleSignedIn,
                    userTokenSnippet = state.googleTokenSnippet,
                    onDismiss = { viewModel.dismissAntiGravityDialog() },
                    onOpenChat = { onChatOpen("antigravity") },
                    onOpenFolder = { directoryPickerLauncher.launch(null) },
                    onSubmitCode = { code, verifier, cid, onResult ->
                        viewModel.submitGoogleAuthCode(code, verifier, cid, onResult)
                    },
                    onSignOut = { viewModel.signOutGoogle() }
                )
            }
        }
    }

    // ── In-App Bridge Terminal Bottom Sheet ───────────────────────────────────
    if (showTerminal) {
        TerminalBottomSheet(onDismiss = { showTerminal = false })
    }
}

@Composable
private fun ProjectNodeRow(
    node: ProjectNode,
    onToggle: () -> Unit,
    onFileClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val indent = (node.level * 16).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (node.isDirectory) onToggle else onFileClick)
            .padding(start = 16.dp + indent, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (node.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = if (node.isExpanded) "Expanded" else "Collapsed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = "File",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (node.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        if (!node.isDirectory && node.sizeBytes > 0) {
            val kb = node.sizeBytes / 1024
            Text(
                text = "${if (kb > 0) kb else 1} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        IconButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color(0xFF6E7681),
                modifier = Modifier.size(14.dp)
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${if (node.isDirectory) "Folder" else "File"}?") },
            text = { Text("Are you sure you want to delete '${node.name}'? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CloneDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone Repository") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("GitHub HTTPS URL") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text("Clone")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GitDialog(
    gitStatus: GitStatusInfo?,
    onCommitAndPush: (String) -> Unit,
    onPull: () -> Unit,
    onCloneClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var commitMessage by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Git Source Control")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (gitStatus == null || !gitStatus.isGitRepo) {
                    Text(
                        "Current directory is not a Git repository.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onCloneClick, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clone Git Repository")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "branch: ${gitStatus.branch}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            "${gitStatus.changedFiles.size} changed file(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (gitStatus.changedFiles.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Pull action button
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(
                            onClick = onPull,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pull Remote", fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (gitStatus.changedFiles.isNotEmpty()) {
                        Text(
                            "Changes:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 100.dp)
                                .background(Color(0xFF1E1E1E), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .padding(6.dp)
                        ) {
                            items(gitStatus.changedFiles) { file ->
                                Text(
                                    text = "• $file",
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = Color(0xFFE6EDF3),
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = commitMessage,
                            onValueChange = { commitMessage = it },
                            label = { Text("Commit message") },
                            placeholder = { Text("Describe changes...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Working tree clean ✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (gitStatus?.isGitRepo == true && gitStatus.changedFiles.isNotEmpty()) {
                Button(
                    onClick = { onCommitAndPush(commitMessage) },
                    enabled = commitMessage.isNotBlank()
                ) {
                    Text("Commit & Push")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun InputDialog(
    title: String,
    label: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
