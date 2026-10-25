package com.aro.code.terminal

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Color helpers for log lines
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun logLineColor(line: String): Color = when {
    line.startsWith("root@arocode") || line.contains("❯") || line.startsWith("aro@code") ->
        Color(0xFF58A6FF) // Prompt in bright blue
    line.contains("✅") || line.contains("Connected") || line.contains("Active") || line.contains("RUNNING") || line.contains("started") ->
        Color(0xFF3FB950) // Success in crisp green
    line.contains("❌") || line.contains("ERROR") || line.contains("error", true) || line.contains("failed", true) ->
        Color(0xFFF85149) // Errors in red
    line.contains("⚠️") || line.contains("WARN") || line.contains("warn", true) ->
        Color(0xFFD29922) // Warnings in amber
    line.contains("╔") || line.contains("║") || line.contains("╚") || line.contains("🤖") ->
        Color(0xFF58A6FF) // Banners in bright cyan
    line.contains("•") || line.contains("Thought Process") || line.contains("tool:") ->
        Color(0xFFBC8CFF) // AI agent thoughts in purple
    line.contains("[Termux Bridge]") || line.contains("INFO") ->
        Color(0xFF29B6F6)
    else -> Color(0xFFC9D1D9) // Standard terminal text in clean light grey
}

// ─────────────────────────────────────────────────────────────────────────────
// Status Badge
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusBadge(status: BridgeStatus, pid: Int?) {
    val (dotColor, label) = when (status) {
        BridgeStatus.RUNNING  -> Color(0xFF4CAF50) to "🟢 Running${if (pid != null) " (PID: $pid)" else ""}"
        BridgeStatus.STARTING -> Color(0xFFFFA726) to "🟡 Starting…"
        BridgeStatus.STOPPING -> Color(0xFFFFA726) to "🟠 Stopping…"
        BridgeStatus.STOPPED  -> Color(0xFFEF5350) to "🔴 Stopped"
        BridgeStatus.ERROR    -> Color(0xFFEF5350) to "❌ Error"
    }
    val animColor by animateColorAsState(dotColor, animationSpec = tween(400), label = "dot")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(animColor)
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Control Buttons
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlButtons(
    status: BridgeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
    onOpenTermux: () -> Unit
) {
    val isRunning = status == BridgeStatus.RUNNING || status == BridgeStatus.STARTING

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1-tap Open Termux button
        Surface(
            onClick = onOpenTermux,
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1F6FEB),
            modifier = Modifier.padding(end = 4.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("📲 Termux", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }

        // Start
        IconButton(onClick = onStart, enabled = !isRunning) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Start Bridge",
                tint = if (!isRunning) Color(0xFF4CAF50) else Color.Gray
            )
        }
        // Restart
        IconButton(onClick = onRestart) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Restart Bridge",
                tint = Color(0xFF29B6F6)
            )
        }
        // Stop
        IconButton(onClick = onStop, enabled = isRunning) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "Stop Bridge",
                tint = if (isRunning) Color(0xFFEF5350) else Color.Gray
            )
        }
        // Clear logs
        IconButton(onClick = onClear) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Clear Logs",
                tint = Color(0xFF9E9E9E)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mobile Keyboard Shortcuts Bar  (Termux-style)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MobileKeyBar(
    onKey: (String) -> Unit,
    onPaste: () -> Unit
) {
    // Row 1 — Navigation & Control (Termux style with Arrow keys & Enter)
    val navKeys = listOf("ESC", "TAB", "CTRL+C", "↑", "↓", "ENTER", "PASTE")
    // Row 2 — Shell & symbols
    val shellKeys = listOf("clear", "-", "/", "~", "|", "&&", "\"", "ls -la")

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117))
            .padding(vertical = 2.dp)
    ) {
        // ── Row 1: Termux Navigation Keys ────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navKeys.forEach { key ->
                val isEnter = key == "ENTER"
                val isArrow = key == "↑" || key == "↓"
                val isCtrlC = key == "CTRL+C"
                val isPaste = key == "PASTE"
                val isAccent = key == "ESC" || key == "TAB"

                Surface(
                    onClick = {
                        if (isPaste) onPaste() else onKey(key)
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isEnter -> Color(0xFF238636)   // GitHub green
                        isArrow -> Color(0xFF1F6FEB)   // Termux / VS Code blue
                        isCtrlC -> Color(0xFF4E1010)   // Red
                        isPaste -> Color(0xFF382554)   // Purple
                        isAccent -> Color(0xFF21262D)  // Dark slate
                        else -> Color(0xFF21262D)
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when {
                            isEnter -> Color(0xFF2EA043)
                            isArrow -> Color(0xFF388BFD)
                            isPaste -> Color(0xFF8957E5)
                            else -> Color(0xFF30363D)
                        }
                    ),
                    modifier = Modifier.height(36.dp)
                ) {
                    Box(
                        Modifier.padding(horizontal = if (isEnter || isArrow) 14.dp else 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isEnter) "⏎ ENTER" else key,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (isEnter || isArrow) 13.sp else 12.sp,
                                fontWeight = if (isEnter || isArrow || isPaste) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = when {
                                isEnter -> Color.White
                                isArrow -> Color.White
                                isCtrlC -> Color(0xFFFF7B72)
                                isPaste -> Color(0xFFD2A8FF)
                                isAccent -> Color(0xFF58A6FF)
                                else -> Color(0xFFC9D1D9)
                            }
                        )
                    }
                }
            }
        }

        // ── Row 2: Shell Shortcuts & Symbols ─────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shellKeys.forEach { key ->
                Surface(
                    onClick = { onKey(key) },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF161B22),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D)),
                    modifier = Modifier.height(28.dp)
                ) {
                    Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = key,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = Color(0xFF8B949E)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Log View Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BridgeLogsTab(logs: List<String>) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom whenever logs change
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(logs.lastIndex)
            }
        }
    }

    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No logs yet…", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = logLineColor(line)
                    ),
                    maxLines = 6
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bash Shell Tab (VS Code Mode)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BashShellTab(
    logs: List<String>,
    onSend: (String) -> Unit,
    onSetupLinux: () -> Unit,
    isLinuxInstalled: Boolean
) {
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var showAgyPromptDialog by remember { mutableStateOf(false) }
    var agyPromptInput by remember { mutableStateOf("") }
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── Dialog 1: Agy Custom Prompt Task Runner ─────────────────────────────
    if (showAgyPromptDialog) {
        AlertDialog(
            onDismissRequest = { showAgyPromptDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🤖", fontSize = 22.sp)
                    Text("Antigravity AI Task", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Task / Prompt darj karein jo agy execute karega:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E)
                    )
                    OutlinedTextField(
                        value = agyPromptInput,
                        onValueChange = { agyPromptInput = it },
                        placeholder = { Text("e.g. build a fastapi server in server.py", color = Color.Gray, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (agyPromptInput.isNotBlank()) {
                            onSend("agy -p \"${agyPromptInput.trim()}\" --dangerously-skip-permissions")
                            agyPromptInput = ""
                        }
                        showAgyPromptDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                ) {
                    Text("Run agy 🚀", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgyPromptDialog = false }) {
                    Text("Cancel", color = Color(0xFF8B949E))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // ── Dialog 2: Termux-Style Interactive Commands Menu ───────────────────
    if (showCommandsDialog) {
        AlertDialog(
            onDismissRequest = { showCommandsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚡", fontSize = 20.sp)
                    Text("Interactive Commands", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🤖 Antigravity AI Agent", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            onClick = {
                                onSend("agy")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1F6FEB)
                        ) {
                            Text("🤖 Start agy", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                        Surface(
                            onClick = {
                                showCommandsDialog = false
                                showAgyPromptDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF238636)
                        ) {
                            Text("💬 Task Prompt...", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSend("agy --continue")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("🔄 Resume Chat", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                BashShellSession.sendCtrlC()
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF4E1010)
                        ) {
                            Text("🛑 Stop (^C)", color = Color(0xFFFF7B72), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }

                    Text("🐍 Python & Development", color = Color(0xFF3FB950), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            onClick = {
                                LinuxSubsystem.deployServerPy(context)
                                onSend("python3 /storage/Download/server.py")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF238636)
                        ) {
                            Text("🚀 server.py", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSend("pip install fastapi uvicorn pydantic sse-starlette")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("📦 Pip Reqs", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSend("pip list")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("📋 Pip List", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }

                    Text("🛠️ Linux Shell & Git", color = Color(0xFFD29922), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            onClick = {
                                onSend("git status")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("🔍 git status", color = Color(0xFF58A6FF), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSend("ls -la")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("📂 ls -la", color = Color(0xFF58A6FF), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSend("pwd")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("📍 pwd", color = Color(0xFF58A6FF), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSend("clear")
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF21262D)
                        ) {
                            Text("🧹 clear", color = Color(0xFF8B949E), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Surface(
                            onClick = {
                                onSetupLinux()
                                showCommandsDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF6E40C9)
                        ) {
                            Text(
                                if (isLinuxInstalled) "🔄 Reinstall Ubuntu" else "📦 Install Ubuntu",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCommandsDialog = false }) {
                    Text("Close", color = Color(0xFF8B949E))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // ── Dialog 3: Termux-Style Model Selection Dialog ──────────────────────
    if (showModelDialog) {
        val currentModel = remember(showModelDialog) { LinuxSubsystem.getSelectedModel(context) }
        val models = listOf(
            "Gemini 3.8 Flash (High)" to "Fastest reasoning, large context window (Recommended)",
            "Gemini 2.5 Pro" to "Advanced problem solving & complex coding",
            "Gemini 2.0 Flash" to "Ultra low-latency, lightweight tasks",
            "Claude 3.5 Sonnet" to "High-level agentic tools and reasoning"
        )

        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🧠", fontSize = 20.sp)
                    Text("Change AI Model", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select default model for Antigravity CLI:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B949E))
                    models.forEach { (modelName, desc) ->
                        val isSelected = currentModel.equals(modelName, ignoreCase = true) ||
                                (modelName.contains("Flash (High)") && currentModel.contains("Flash", true))
                        Surface(
                            onClick = {
                                LinuxSubsystem.updateSelectedModel(context, modelName)
                                Toast.makeText(context, "Model switched to: $modelName", Toast.LENGTH_SHORT).show()
                                BashShellSession.activeSession?.addLog("🧠 [System] Model set to: $modelName")
                                showModelDialog = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF1F3A24) else Color(0xFF21262D),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3FB950)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(if (isSelected) "🟢" else "⚪", fontSize = 14.sp)
                                Column {
                                    Text(modelName, color = if (isSelected) Color(0xFF3FB950) else Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text(desc, color = Color(0xFF8B949E), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Cancel", color = Color(0xFF8B949E))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // ── Dialog 4: Termux-Style Settings Dialog ─────────────────────────────
    if (showSettingsDialog) {
        val isAuth = remember(showSettingsDialog) { LinuxSubsystem.isLoggedIn(context) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚙️", fontSize = 20.sp)
                    Text("Terminal & agy Settings", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Google Authentication", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Text(if (isAuth) "🟢 Connected" else "🔴 Login Needed", color = if (isAuth) Color(0xFF3FB950) else Color(0xFFF85149), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            if (isAuth) {
                                Button(
                                    onClick = {
                                        LinuxSubsystem.resetAuth(context)
                                        Toast.makeText(context, "Logged out! Re-authenticating...", Toast.LENGTH_SHORT).show()
                                        onSend("agy")
                                        showSettingsDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1A1A)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("🔑 Re-authenticate / Logout", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            onSend("clear")
                            showSettingsDialog = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🧹", fontSize = 16.sp)
                            Text("Clear Screen & History", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Surface(
                        onClick = {
                            TermuxBridgeService.openTermuxSetup(context)
                            showSettingsDialog = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📲", fontSize = 16.sp)
                            Text("Fix Termux Config (External Apps)", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Surface(
                        onClick = {
                            onSetupLinux()
                            showSettingsDialog = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isLinuxInstalled) Color(0xFF21262D) else Color(0xFF6E40C9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (isLinuxInstalled) "🔄" else "📦", fontSize = 16.sp)
                            Text(
                                if (isLinuxInstalled) "Reinstall Ubuntu Linux Container" else "Install In-App Ubuntu Linux Subsystem (22.04 LTS)",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (!isLinuxInstalled) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close", color = Color(0xFF8B949E))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    val activeSession = BashShellSession.activeSession
    val shellLogs = activeSession?.logs ?: remember(logs) { logs.filter { it.startsWith("💻") } }

    LaunchedEffect(shellLogs.size) {
        if (shellLogs.isNotEmpty()) {
            coroutineScope.launch { listState.animateScrollToItem(shellLogs.lastIndex) }
        }
    }

    LaunchedEffect(input) {
        if (input.isNotEmpty() && shellLogs.isNotEmpty()) {
            listState.animateScrollToItem(shellLogs.lastIndex)
        }
    }

    val submitCommand: (String) -> Unit = { raw ->
        val trimmed = raw.trim()
        if (trimmed.isNotBlank()) {
            val recent = shellLogs.takeLast(10).joinToString("\n")
            val isAgyMenu = recent.contains("Select login method", ignoreCase = true) ||
                            (recent.contains("Antigravity CLI", ignoreCase = true) && recent.contains("Google OAuth"))

            if (isAgyMenu && (trimmed == "1" || trimmed.equals("Google OAuth", ignoreCase = true))) {
                // User typed 1 or Google OAuth on agy menu -> select Google OAuth!
                BashShellSession.sendRaw("\r")
                input = ""
            } else if (isAgyMenu && (trimmed == "2" || trimmed.contains("Cloud", ignoreCase = true))) {
                // User typed 2 on agy menu -> move down + enter
                BashShellSession.sendRaw("\u001B[B\r")
                input = ""
            } else {
                if (history.isEmpty() || history.last() != trimmed) {
                    history.add(trimmed)
                }
                historyIndex = -1
                onSend(trimmed)
                input = ""
            }
        } else {
            // Empty input: user pressed Send or Enter on blank field -> send raw \r
            BashShellSession.sendRaw("\r")
        }
    }

    Column(Modifier.fillMaxSize()) {

        // ── Multi-Session Tabs Bar (VS Code / Termux Style) ─────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF0D1117))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val sessions = BashShellSession.sessions
            val activeIdx = BashShellSession.activeSessionIndex

            sessions.forEachIndexed { idx, session ->
                val isSelected = idx == activeIdx
                Surface(
                    onClick = { BashShellSession.switchSession(idx) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF21262D),
                    tonalElevation = if (isSelected) 4.dp else 0.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.White else Color(0xFF8B949E),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (sessions.size > 1) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Tab",
                                tint = if (isSelected) Color.White else Color(0xFF8B949E),
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable { BashShellSession.closeSession(idx) }
                            )
                        }
                    }
                }
            }

            // [+] New Session Button
            Surface(
                onClick = { BashShellSession.createSession(context) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF238636)
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Session",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text("+ New Session", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }

        // ── In-App Ubuntu Linux Subsystem Installation Banner (When not installed) ──
        if (!isLinuxInstalled) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color(0xFF6E40C9),
                shape = RoundedCornerShape(8.dp),
                onClick = onSetupLinux
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "📦 In-App Ubuntu Linux Subsystem",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Ubuntu 22.04 LTS (Git, Python3, Pip, agy CLI) install karein",
                            color = Color(0xFFE1D5F5),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Button(
                        onClick = onSetupLinux,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("📥 Install", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── 1-Tap Google OAuth Login Button (Direct Chrome Launch & Multi-line Stitched) ─
        val oauthUrl = remember(shellLogs.size) {
            val urlRegex = Regex("https://accounts\\.google\\.com/o/oauth2/auth\\?[A-Za-z0-9_.~%!$&'()*+,;=:@/?#\\-]+")
            // 1. Check each log line from newest to oldest for clean full URL
            shellLogs.asReversed().firstNotNullOfOrNull { line ->
                urlRegex.find(line)?.value
            } ?: run {
                // 2. Fallback: Scan across all logs taking only valid URL characters (stops at box-drawing '─', emoji, or text)
                val allText = shellLogs.joinToString("\n")
                val start = allText.indexOf("https://accounts.google.com/o/oauth2/auth?")
                if (start != -1) {
                    val sb = StringBuilder()
                    for (ch in allText.substring(start).take(2500)) {
                        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "_-.~%!$&'()*+,;=:@/?#") {
                            sb.append(ch)
                        } else if (ch == '\r' || ch == '\n' || ch == ' ') {
                            continue // Skip wrapping whitespace
                        } else {
                            break // Stop at any decorative/emoji/boundary character like ─ or 💻
                        }
                    }
                    val url = sb.toString()
                    if (url.contains("client_id") && url.contains("code_challenge")) url else null
                } else null
            }
        }

        // ── 1-Tap Google OAuth Login Method Selector (Agy TUI Helper) ─────────
        val isAgyMenu = remember(shellLogs.size) {
            val recent = shellLogs.takeLast(12).joinToString("\n")
            recent.contains("Select login method", ignoreCase = true) ||
            (recent.contains("Antigravity CLI", ignoreCase = true) && recent.contains("Google OAuth"))
        }

        if (isAgyMenu && oauthUrl == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color(0xFF161B22),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF238636))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "👇 Agy Menu Select:",
                        color = Color(0xFF58A6FF),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { BashShellSession.sendRaw("\r") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("1. 🔑 Google OAuth (Enter)", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { BashShellSession.sendRaw("\u001B[B\r") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("2. GCP", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (oauthUrl != null) {
            val clipboardManager = LocalClipboardManager.current
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                color = Color(0xFF1A73E8),
                shape = RoundedCornerShape(8.dp),
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "🔑 1-Tap Google Login (Open in Chrome)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(oauthUrl))
                            Toast.makeText(context, "Full URL Copied! 📋", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("📋 Copy Link", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ── 1-Tap Auth Code Paste Helper ────────────────────────────────────
        val isWaitingAuthCode = remember(shellLogs.size) {
            val recent = shellLogs.takeLast(10).joinToString("\n")
            recent.contains("authorization code", ignoreCase = true) ||
            (recent.contains("paste", ignoreCase = true) && recent.contains("code", ignoreCase = true))
        }

        if (isWaitingAuthCode) {
            val clipboardManager = LocalClipboardManager.current
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color(0xFF261C38),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8957E5))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "📋 Code Copy Kar Liya?",
                        color = Color(0xFFD2A8FF),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Button(
                        onClick = {
                            val clip = clipboardManager.getText()?.text?.trim()
                            if (!clip.isNullOrBlank()) {
                                BashShellSession.sendRaw(clip + "\r")
                                Toast.makeText(context, "Code paste ho gaya! 🚀", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Pehle Chrome se code copy karein!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8957E5)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("📋 1-Tap Paste & Connect", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Agy Interactive Approval Strip (Only when Agy is asking for input) ──
        val isAgyInteractive = remember(shellLogs.size) {
            val recent = shellLogs.takeLast(8).joinToString("\n")
            recent.contains("you:", ignoreCase = true) ||
            recent.contains("Thought Process", ignoreCase = true) ||
            (recent.contains("Navigate", ignoreCase = true) && recent.contains("Confirm", ignoreCase = true)) ||
            recent.contains("tool:", ignoreCase = true)
        }

        if (isAgyInteractive) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color(0xFF0D2137),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F6FEB))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { BashShellSession.sendRaw("\r") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("⏎ Confirm", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { BashShellSession.sendRaw("\u001B[B\r") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("[Next]", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = { BashShellSession.sendRaw("s\r") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("[Skip]", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = { BashShellSession.sendRaw("continue\r") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Continue→", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { BashShellSession.sendCtrlC() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E1010)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) { Text("^C", color = Color(0xFFFF7B72), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // ── Main Terminal Viewport (Crisp Monospace, Clean output) ──────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(shellLogs) { line ->
                val cleanLine = line.removePrefix("💻 ")
                if (cleanLine.isNotBlank()) {
                    Text(
                        text = cleanLine,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = logLineColor(cleanLine)
                        )
                    )
                }
            }
        }

        // ── Termux-Style Interactive Menu Bar (Commands, Model, Settings) ───
        val isAuthNow = remember(shellLogs.size) { LinuxSubsystem.isLoggedIn(context) }
        val activeModelName = remember(shellLogs.size, showModelDialog) { LinuxSubsystem.getSelectedModel(context) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ⚡ Commands Menu
            Surface(
                onClick = { showCommandsDialog = true },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262D)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("⚡", fontSize = 12.sp)
                    Text("Commands", color = Color(0xFF58A6FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            // 🧠 Model Menu
            Surface(
                onClick = { showModelDialog = true },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262D)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("🧠", fontSize = 12.sp)
                    val shortModel = when {
                        activeModelName.contains("3.8", true) -> "3.8 Flash"
                        activeModelName.contains("2.5", true) -> "2.5 Pro"
                        activeModelName.contains("2.0", true) -> "2.0 Flash"
                        activeModelName.contains("Claude", true) -> "Claude"
                        else -> "Model"
                    }
                    Text("Model ($shortModel)", color = Color(0xFFD29922), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            // ⚙️ Settings Menu
            Surface(
                onClick = { showSettingsDialog = true },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262D)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("⚙️", fontSize = 12.sp)
                    Text("Settings", color = Color(0xFF3FB950), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            // 📦 Install Ubuntu (if not installed)
            if (!isLinuxInstalled) {
                Surface(
                    onClick = onSetupLinux,
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF6E40C9)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("📦", fontSize = 11.sp)
                        Text("Install Ubuntu", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // 🚀 Run server.py (when installed)
                Surface(
                    onClick = {
                        LinuxSubsystem.deployServerPy(context)
                        onSend("python3 /storage/Download/server.py")
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF238636)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("🚀", fontSize = 11.sp)
                        Text("server.py", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 🤖 Quick Run agy
            Surface(
                onClick = { onSend("agy") },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1F6FEB)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🤖", fontSize = 11.sp)
                    Text("agy", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            // 🔄 Quick Resume
            Surface(
                onClick = { onSend("agy --continue") },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262D)
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🔄", fontSize = 11.sp)
                    Text("Resume", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelSmall)
                }
            }

            // 🧹 Clear
            Surface(
                onClick = { onSend("clear") },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262D)
            ) {
                Text("🧹 Clear", color = Color(0xFF8B949E), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }

            // 🟢 Status Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAuthNow) Color(0xFF1A3B1A) else Color(0xFF3B1A1A)
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (isAuthNow) Color(0xFF3FB950) else Color(0xFFF85149)))
                    Text(if (isAuthNow) "Connected" else "Login", color = if (isAuthNow) Color(0xFF3FB950) else Color(0xFFF85149), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        val clipboardManager = LocalClipboardManager.current

        // Mobile key shortcuts (Termux-style)
        MobileKeyBar(
            onKey = { key ->
                when (key) {
                    "CTRL+C" -> BashShellSession.sendCtrlC()
                    "ESC"    -> BashShellSession.sendRaw("\u001B")
                    "TAB"    -> {
                        if (input.isNotBlank()) {
                            // Send current input + TAB for shell autocomplete
                            BashShellSession.sendRaw(input + "\t")
                            input = ""
                        } else {
                            BashShellSession.sendRaw("\t")
                        }
                    }
                    "↑" -> {
                        // Send ANSI up-arrow — works for both TUI (agy menu navigation) and shell history
                        BashShellSession.sendRaw("\u001B[A")
                        // Also update history index for text input field
                        if (history.isNotEmpty()) {
                            historyIndex = (historyIndex + 1).coerceAtMost(history.lastIndex)
                            input = history[history.lastIndex - historyIndex]
                        }
                    }
                    "↓" -> {
                        // Send ANSI down-arrow — works for both TUI (agy menu navigation) and shell history
                        BashShellSession.sendRaw("\u001B[B")
                        if (historyIndex > 0) {
                            historyIndex--
                            input = history[history.lastIndex - historyIndex]
                        } else if (historyIndex == 0) {
                            historyIndex = -1
                            input = ""
                        }
                    }
                    "ENTER"  -> submitCommand(input)
                    "clear"  -> submitCommand("clear")
                    "ls -la" -> submitCommand("ls -la")
                    else     -> input += key
                }
            },
            onPaste = {
                val clip = clipboardManager.getText()?.text
                if (!clip.isNullOrBlank()) {
                    input += clip
                } else {
                    Toast.makeText(context, "Clipboard empty!", Toast.LENGTH_SHORT).show()
                }
            }
        )

        Divider(color = Color(0xFF333333))

        // Command input row (VS Code prompt)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val active = BashShellSession.activeSession
            val promptText = if (active?.isLinuxContainerActive == true) {
                "root@arocode ❯ "
            } else {
                "${File(active?.cwd ?: "/storage/emulated/0/Download").name.ifEmpty { "root" }} ❯ "
            }
            Text(
                promptText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (active?.isLinuxContainerActive == true) Color(0xFF58A6FF) else Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
            )
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF4CAF50),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                placeholder = {
                    Text("command (e.g. ls, python, pip)", color = Color(0xFF555555), fontSize = 11.sp)
                },
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitCommand(input) })
            )
            IconButton(
                onClick = { submitCommand(input) }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF4CAF50))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main TerminalBottomSheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalBottomSheet(
    onDismiss: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pid by viewModel.pid.collectAsState()
    val isLinuxInstalled by viewModel.isLinuxInstalled.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("⚡ Bridge Logs", "❯_ Bash Shell")

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LaunchedEffect(Unit) {
        viewModel.startShell(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161B22),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF555555)) },
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {

            // ── Header: Status + Controls ──────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1117))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusBadge(status, pid)
                ControlButtons(
                    status = status,
                    onStart      = { viewModel.startBridge(context) },
                    onStop       = {
                        viewModel.stopBridge(context)
                        viewModel.sendCtrlC()
                    },
                    onRestart    = { viewModel.restartBridge(context) },
                    onClear      = { viewModel.clearLogs() },
                    onOpenTermux = { viewModel.openTermux(context) }
                )
            }

            // ── Bridge Info Row ────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1117))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "📍 ${TermuxBridgeService.state.activeUrl.value}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF29B6F6)
                )
                Text(
                    "📁 /storage/…/Download",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9E9E9E)
                )
            }

            // ── Tab Bar ───────────────────────────────────────────────────
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0D1117),
                contentColor = Color.White,
                indicator = { tabPositions ->
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[selectedTab])
                            .height(2.dp)
                            .background(Color(0xFF29B6F6))
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedTab == index) Color(0xFF29B6F6) else Color(0xFF9E9E9E)
                            )
                        }
                    )
                }
            }

            // ── Tab Content ───────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> BridgeLogsTab(logs)
                    1 -> BashShellTab(
                        logs = logs,
                        onSend = { viewModel.sendBashCommand(it) },
                        onSetupLinux = { viewModel.installMiniLinux(context) },
                        isLinuxInstalled = isLinuxInstalled
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mini Bridge Status Pill (for WorkspaceScreen)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BridgeStatusPill(
    status: BridgeStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUrl by TermuxBridgeService.state.activeUrl.collectAsState()
    val currentPort = remember(currentUrl) {
        runCatching { java.net.URL(currentUrl).port }.getOrNull()?.takeIf { it > 0 } ?: 8000
    }

    val (bgColor, label) = when (status) {
        BridgeStatus.RUNNING  -> Color(0xFF1B5E20) to "🟢 Bridge :$currentPort"
        BridgeStatus.STARTING -> Color(0xFF4E342E) to "🟡 Starting…"
        BridgeStatus.STOPPING -> Color(0xFF4E342E) to "🟠 Stopping…"
        BridgeStatus.STOPPED  -> Color(0xFF4E1010) to "🔴 Bridge Offline"
        BridgeStatus.ERROR    -> Color(0xFF4E1010) to "❌ Bridge Error"
    }
    val animBg by animateColorAsState(bgColor, animationSpec = tween(400), label = "pillBg")

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = animBg,
        tonalElevation = 4.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Open Terminal",
                modifier = Modifier.size(14.dp),
                tint = Color.White
            )
        }
    }
}
