package com.aro.code.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onFileOpen: (String) -> Unit,
    onChatOpen: () -> Unit,
    viewModel: WorkspaceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aro Code") },
                actions = {
                    IconButton(onClick = onChatOpen) {
                        Icon(Icons.Default.Chat, contentDescription = "AI Chat")
                    }
                    IconButton(onClick = { viewModel.triggerCloudBuild() }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Build")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.clonePrompt() }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Clone Repo")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            state.statusMessage?.let {
                Text(it, modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.files) { file ->
                    FileRow(file = file, onClick = { onFileOpen(file.absolutePath) })
                    HorizontalDivider()
                }
            }

            if (state.showCloneDialog) {
                CloneDialog(
                    onConfirm = { url -> viewModel.cloneRepo(url) },
                    onDismiss = { viewModel.dismissCloneDialog() }
                )
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${file.length() / 1024} KB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CloneDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone Repository") },
        text  = {
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
