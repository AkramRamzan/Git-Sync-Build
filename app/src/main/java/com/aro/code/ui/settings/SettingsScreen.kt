package com.aro.code.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar on success or error
    LaunchedEffect(state.successMessage, state.errorMessage) {
        val msg = state.successMessage ?: state.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys & Credentials") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Gemini API Key Card ────────────────────────────────────────
            ApiKeyCard(
                title        = "Gemini API Key",
                subtitle     = "Used for AI code generation (gemini-2.0-flash)",
                currentMask  = state.geminiKeyMasked,
                placeholder  = "AIza...",
                onSave       = { viewModel.saveGeminiKey(it) },
                onDelete     = { viewModel.deleteGeminiKey() }
            )

            // ── GitHub Personal Access Token Card ─────────────────────────
            ApiKeyCard(
                title        = "GitHub Token (PAT)",
                subtitle     = "Used for clone, push, and cloud build dispatch",
                currentMask  = state.githubTokenMasked,
                placeholder  = "ghp_...",
                onSave       = { viewModel.saveGithubToken(it) },
                onDelete     = { viewModel.deleteGithubToken() }
            )

            // ── Danger Zone ───────────────────────────────────────────────
            DangerZoneCard(onClearAll = { viewModel.deleteAllCredentials() })
        }
    }
}

// ── Reusable API Key Card ────────────────────────────────────────────────────
@Composable
private fun ApiKeyCard(
    title: String,
    subtitle: String,
    currentMask: String?,
    placeholder: String,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var inputValue    by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Header
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Current value badge
            if (currentMask != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text     = "Current: $currentMask",
                        style    = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else {
                Text(
                    "⚠ Not set",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            // Input field
            OutlinedTextField(
                value         = inputValue,
                onValueChange = { inputValue = it },
                label         = { Text(if (currentMask != null) "New value" else "Enter key") },
                placeholder   = { Text(placeholder) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon  = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide" else "Show"
                        )
                    }
                }
            )

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                // Delete button (only shown if key exists)
                if (currentMask != null) {
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        colors  = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }

                // Save / Update button
                Button(
                    onClick  = { onSave(inputValue); inputValue = "" },
                    enabled  = inputValue.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (currentMask != null) "Update" else "Save")
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title  = { Text("Delete $title?") },
            text   = { Text("This key will be permanently removed from the secure store.") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(); showDeleteDialog = false },
                    colors  = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── Danger Zone Card ─────────────────────────────────────────────────────────
@Composable
private fun DangerZoneCard(onClearAll: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Danger Zone",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error)
            Text("Clear all saved credentials from the secure store.",
                style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { showDialog = true },
                colors  = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Clear All Credentials") }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title  = { Text("Clear All Credentials?") },
            text   = { Text("All API keys and tokens will be permanently deleted. You will need to re-enter them to use the app.") },
            confirmButton  = {
                TextButton(
                    onClick = { onClearAll(); showDialog = false },
                    colors  = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear All") }
            },
            dismissButton  = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }
}
