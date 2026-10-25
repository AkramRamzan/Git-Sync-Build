package com.aro.code.ui.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            var modelMenuExpanded by remember { mutableStateOf(false) }

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
                    // Module 2.1 & 2.2 — Model Switcher Dropdown
                    Box {
                        Surface(
                            onClick = { modelMenuExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.selectedModel.displayName.take(16),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Model",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            com.aro.code.ai.GeminiModel.entries.forEach { model ->
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
                                        viewModel.selectModel(model)
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Open SAF Folder
                    IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Project Folder")
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

                    Row {
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

            state.statusMessage?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
                            "No files found in workspace.",
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
                    items(state.projectNodes) { node ->
                        ProjectNodeRow(
                            node = node,
                            onToggle = { viewModel.toggleDirectory(node) },
                            onFileClick = {
                                val openPath = node.uri?.toString() ?: node.file?.absolutePath ?: node.path
                                onFileOpen(openPath)
                            }
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
    onFileClick: () -> Unit
) {
    val indent = (node.level * 16).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (node.isDirectory) onToggle else onFileClick)
            .padding(start = 16.dp + indent, end = 16.dp, top = 10.dp, bottom = 10.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
