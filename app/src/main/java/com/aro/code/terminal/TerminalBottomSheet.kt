package com.aro.code.terminal

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Color helpers for log lines (Compact Termux-style)
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
// Status Badge (Compact UI scaling)
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
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(animColor)
        )
        Text(label, style = TextStyle(fontSize = 9.sp, color = Color.White))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Control Buttons (Compact mobile scaling with Minimize (-) toggle)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlButtons(
    status: BridgeStatus,
    isMinimized: Boolean,
    onToggleMinimize: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
    onOpenTermux: () -> Unit
) {
    val isRunning = status == BridgeStatus.RUNNING || status == BridgeStatus.STARTING

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // 1-tap Open Termux button
        Surface(
            onClick = onOpenTermux,
            shape = RoundedCornerShape(5.dp),
            color = Color(0xFF1F6FEB),
            modifier = Modifier.padding(end = 1.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("📲 Termux", style = TextStyle(fontSize = 9.sp, color = Color.White))
            }
        }

        // Start
        Surface(
            onClick = onStart,
            shape = RoundedCornerShape(5.dp),
            color = Color(0xFF21262D),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Start Bridge",
                    tint = if (!isRunning) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        // Restart
        Surface(
            onClick = onRestart,
            shape = RoundedCornerShape(5.dp),
            color = Color(0xFF21262D),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Restart Bridge",
                    tint = Color(0xFF29B6F6),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        // Stop
        Surface(
            onClick = onStop,
            shape = RoundedCornerShape(5.dp),
            color = Color(0xFF21262D),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop Bridge",
                    tint = if (isRunning) Color(0xFFEF5350) else Color.Gray,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        // Clear logs
        Surface(
            onClick = onClear,
            shape = RoundedCornerShape(5.dp),
            color = Color(0xFF21262D),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Clear Logs",
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Minimize / Restore Toggle Button (-)
        Surface(
            onClick = onToggleMinimize,
            shape = RoundedCornerShape(5.dp),
            color = if (isMinimized) Color(0xFF1F6FEB) else Color(0xFF21262D),
            border = BorderStroke(1.dp, if (isMinimized) Color(0xFF58A6FF) else Color(0xFF30363D)),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isMinimized) "＋" else "－",
                    color = if (isMinimized) Color.White else Color(0xFF58A6FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mobile Keyboard Shortcuts Bar (Termux-style compact with + button)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MobileKeyBar(
    onKey: (String) -> Unit,
    onPaste: () -> Unit,
    onNewSession: () -> Unit
) {
    var isCtrlSticky by remember { mutableStateOf(false) }
    var isAltSticky by remember { mutableStateOf(false) }

    // Row 1 — Navigation & Control (Termux style with Arrow keys, sticky CTRL/ALT, SPACE, Enter, PASTE, and + button at very end)
    val navKeys = listOf("ESC", "TAB", "CTRL", "ALT", "CTRL+C", "CTRL+D", "↑", "↓", "←", "→", "SPACE", "ENTER", "PASTE", "+")
    // Row 2 — Shell & symbols
    val shellKeys = listOf("clear", "-", "/", "~", "|", "&&", "\"", "ls -la")

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117))
            .padding(vertical = 1.dp)
    ) {
        // ── Row 1: Termux Navigation Keys ────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navKeys.forEach { key ->
                val isEnter = key == "ENTER"
                val isSpace = key == "SPACE"
                val isArrow = key == "↑" || key == "↓" || key == "←" || key == "→"
                val isCtrlC = key == "CTRL+C"
                val isCtrlD = key == "CTRL+D"
                val isCtrl = key == "CTRL"
                val isAlt = key == "ALT"
                val isPaste = key == "PASTE"
                val isPlus = key == "+"
                val isAccent = key == "ESC" || key == "TAB"
                val isHighlighted = (isCtrl && isCtrlSticky) || (isAlt && isAltSticky)

                Surface(
                    onClick = {
                        when (key) {
                            "CTRL" -> isCtrlSticky = !isCtrlSticky
                            "ALT"  -> isAltSticky = !isAltSticky
                            "PASTE" -> onPaste()
                            "+" -> onNewSession()
                            else -> {
                                if (isCtrlSticky) {
                                    isCtrlSticky = false
                                    when (key) {
                                        "CTRL+C" -> onKey("CTRL+C")
                                        "CTRL+D" -> onKey("CTRL+D")
                                        else -> onKey(key)
                                    }
                                } else if (isAltSticky) {
                                    isAltSticky = false
                                    onKey("\u001B$key")
                                } else {
                                    onKey(key)
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = when {
                        isHighlighted -> Color(0xFF388BFD)
                        isPlus -> Color(0xFF238636)    // Termux green for add session
                        isEnter -> Color(0xFF238636)   // GitHub green
                        isSpace -> Color(0xFF1F6FEB)   // Crisp blue for space select
                        isArrow -> Color(0xFF1F6FEB)   // Termux / VS Code blue
                        isCtrlC || isCtrlD -> Color(0xFF4E1010)   // Red
                        isPaste -> Color(0xFF382554)   // Purple
                        isCtrl || isAlt -> Color(0xFF21262D)
                        isAccent -> Color(0xFF21262D)
                        else -> Color(0xFF21262D)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isHighlighted -> Color(0xFF58A6FF)
                            isPlus -> Color(0xFF2EA043)
                            isEnter -> Color(0xFF2EA043)
                            isSpace -> Color(0xFF388BFD)
                            isArrow -> Color(0xFF388BFD)
                            isPaste -> Color(0xFF8957E5)
                            else -> Color(0xFF30363D)
                        }
                    ),
                    modifier = Modifier.height(25.dp)
                ) {
                    Box(
                        Modifier.padding(horizontal = if (isEnter || isSpace) 8.dp else if (isPlus) 7.dp else 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isEnter) "⏎ ENTER" else if (isSpace) "␣ SPACE" else if (isPlus) "＋" else key,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (isPlus) 11.sp else 9.sp,
                                fontWeight = if (isEnter || isSpace || isArrow || isPaste || isHighlighted || isPlus) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = when {
                                isHighlighted || isEnter || isSpace || isArrow || isPlus -> Color.White
                                isCtrlC || isCtrlD -> Color(0xFFFF7B72)
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
                .padding(horizontal = 4.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shellKeys.forEach { key ->
                Surface(
                    onClick = { onKey(key) },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF161B22),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    modifier = Modifier.height(21.dp)
                ) {
                    Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = key,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
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

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(logs.lastIndex)
            }
        }
    }

    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No logs yet…", color = Color(0xFF9E9E9E), style = TextStyle(fontSize = 10.sp))
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = logLineColor(line)
                    ),
                    maxLines = 6
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bash Shell Tab (Termux-style Navigation Drawer + Full Viewport)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BashShellTab(
    logs: List<String>,
    onSend: (String) -> Unit,
    onSetupLinux: () -> Unit,
    isLinuxInstalled: Boolean,
    refreshTrigger: Int = 0
) {
    var showAgyPromptDialog by remember { mutableStateOf(false) }
    var agyPromptInput by remember { mutableStateOf("") }
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // ── Dialog 1: Agy Custom Prompt Task Runner ─────────────────────────────
    if (showAgyPromptDialog) {
        AlertDialog(
            onDismissRequest = { showAgyPromptDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🤖", fontSize = 14.sp)
                    Text("Agy Agent Task Prompt", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Google Antigravity CLI ko direct instruction dein. Agent background mein file create aur terminal commands run karega:",
                        color = Color(0xFFC9D1D9),
                        style = TextStyle(fontSize = 11.sp)
                    )
                    OutlinedTextField(
                        value = agyPromptInput,
                        onValueChange = { agyPromptInput = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        placeholder = { Text("e.g. create a python web scraper for tech news...", color = Color(0xFF8B949E), style = TextStyle(fontSize = 11.sp)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF58A6FF),
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedContainerColor = Color(0xFF0D1117),
                            unfocusedContainerColor = Color(0xFF0D1117)
                        ),
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val prompt = agyPromptInput.trim()
                        if (prompt.isNotBlank()) {
                            onSend("agy \"$prompt\"")
                        }
                        agyPromptInput = ""
                        showAgyPromptDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Run Agent 🚀", color = Color.White, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgyPromptDialog = false }) {
                    Text("Cancel", color = Color(0xFF8B949E), style = TextStyle(fontSize = 11.sp))
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚡", fontSize = 14.sp)
                    Text("Quick Command Shortcuts", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Google Antigravity CLI (agy)", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 10.sp))

                    Surface(
                        onClick = {
                            onSend("agy")
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🤖 agy (Start Interactive Session)", color = Color.White, style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(8.dp))
                    }

                    Surface(
                        onClick = {
                            showCommandsDialog = false
                            showAgyPromptDialog = true
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1F6FEB),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💬 agy \"<prompt>\" (Custom Task Prompt)", color = Color.White, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(8.dp))
                    }

                    Surface(
                        onClick = {
                            onSend("agy --continue")
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔄 agy --continue (Resume last session)", color = Color.White, style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(8.dp))
                    }

                    Surface(
                        onClick = {
                            BashShellSession.sendCtrlC()
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF4E1010),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🛑 Cancel/Interrupt (^C)", color = Color(0xFFFF7B72), style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(8.dp))
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Python & Bridge Server", color = Color(0xFF3FB950), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 10.sp))

                    Surface(
                        onClick = {
                            LinuxSubsystem.deployServerPy(context)
                            onSend("python3 /storage/Download/server.py")
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF238636),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🚀 python3 /storage/Download/server.py", color = Color.White, style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(8.dp))
                    }

                    Surface(
                        onClick = {
                            onSend("python3 --version && pip list")
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🐍 Check Python3 & Pip packages", color = Color.White, style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(8.dp))
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("System & Diagnostics", color = Color(0xFFD29922), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 10.sp))

                    Surface(
                        onClick = {
                            onSend("clear")
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧹 clear", color = Color.White, style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(8.dp))
                    }

                    Surface(
                        onClick = {
                            onSend("uname -a && id")
                            showCommandsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🐧 Kernel & User Info", color = Color.White, style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCommandsDialog = false }) {
                    Text("Close", color = Color(0xFF8B949E), style = TextStyle(fontSize = 10.sp))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // ── Dialog 3: Termux-Style Model Selection Dialog ──────────────────────
    if (showModelDialog) {
        val currentModel = remember(showModelDialog) { LinuxSubsystem.getSelectedModel(context) }
        val models = listOf(
            "gemini-2.5-flash" to "⚡ Gemini 2.5 Flash (Ultra Fast & Responsive)",
            "gemini-2.5-pro" to "🧠 Gemini 2.5 Pro (Deep Reasoning & Complex Code)",
            "gemini-2.0-flash" to "🚀 Gemini 2.0 Flash (Stable)",
            "claude-3-5-sonnet" to "🎭 Claude 3.5 Sonnet (Advanced Coding)"
        )

        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🧠", fontSize = 14.sp)
                    Text("AI Model Selection", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Antigravity CLI ke liye default AI model select karein:", color = Color(0xFF8B949E), style = TextStyle(fontSize = 10.sp))
                    models.forEach { (modelId, modelName) ->
                        val isSelected = currentModel.contains(modelId, ignoreCase = true)
                        Surface(
                            onClick = {
                                LinuxSubsystem.updateSelectedModel(context, modelId)
                                BashShellSession.activeSession?.addLog("🧠 [System] Model set to: $modelName")
                                showModelDialog = false
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF21262D),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(modelName, color = Color.White, style = TextStyle(fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal))
                                if (isSelected) Text("✔", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Close", color = Color(0xFF8B949E), style = TextStyle(fontSize = 10.sp))
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚙️", fontSize = 14.sp)
                    Text("Terminal Settings & Health", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Subsystem Status", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 10.sp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0D1117),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Ubuntu 22.04 LTS: ${if (isLinuxInstalled) "✅ Installed" else "❌ Not Installed"}", color = Color.White, style = TextStyle(fontSize = 10.sp))
                            Text("Google OAuth: ${if (isAuth) "✅ Logged in" else "⚠️ Not Logged in"}", color = Color.White, style = TextStyle(fontSize = 10.sp))
                            Text("Active Sessions: ${BashShellSession.sessions.size}", color = Color.White, style = TextStyle(fontSize = 10.sp))
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Actions", color = Color(0xFFD29922), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 10.sp))

                    Surface(
                        onClick = {
                            LinuxSubsystem.resetAuth(context)
                            Toast.makeText(context, "Logged out from Antigravity CLI", Toast.LENGTH_SHORT).show()
                            showSettingsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF3E1F1F),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🚪 Logout Google Session", color = Color(0xFFFF7B72), style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        }
                    }

                    Surface(
                        onClick = {
                            BashShellSession.activeSession?.restart(context)
                            showSettingsDialog = false
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔄 Restart Active Session", color = Color.White, style = TextStyle(fontSize = 10.sp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close", color = Color(0xFF8B949E), style = TextStyle(fontSize = 10.sp))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    val activeSession = BashShellSession.activeSession
    val shellLogs = activeSession?.logs ?: remember(logs) { logs.filter { it.startsWith("💻") } }

    LaunchedEffect(Unit) {
        BashShellSession.start(context)
    }

    val sessions = BashShellSession.sessions
    val activeIdx = BashShellSession.activeSessionIndex

    // ── 1-Tap Google OAuth Login URL (Scanned from active logs) ───────────────
    val oauthUrl = remember(shellLogs.size, activeSession?.id, refreshTrigger) {
        val urlRegex = Regex("https://accounts\\.google\\.com/o/oauth2/auth\\?[A-Za-z0-9_.~%!$&'()*+,;=:@/?#\\-]+")
        shellLogs.asReversed().firstNotNullOfOrNull { line ->
            urlRegex.find(line)?.value
        } ?: run {
            val allText = shellLogs.joinToString("\n")
            val start = allText.indexOf("https://accounts.google.com/o/oauth2/auth?")
            if (start != -1) {
                val sb = StringBuilder()
                for (ch in allText.substring(start).take(2500)) {
                    if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "_-.~%!$&'()*+,;=:@/?#") {
                        sb.append(ch)
                    } else if (ch == '\r' || ch == '\n' || ch == ' ') {
                        continue
                    } else {
                        break
                    }
                }
                val url = sb.toString()
                if (url.contains("client_id") && url.contains("code_challenge")) url else null
            } else null
        }
    }

    val isAuthNow = remember(shellLogs.size, refreshTrigger) { LinuxSubsystem.isLoggedIn(context) }
    val clipboardManager = LocalClipboardManager.current

    // ── Navigation Drawer for Sessions (Swipe from left side or tap ☰) ─────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF161B22),
                drawerContentColor = Color.White,
                modifier = Modifier.width(280.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(12.dp)
                ) {
                    // Drawer Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text("💻", fontSize = 16.sp)
                        Column {
                            Text("AroCode Termux", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Active Terminal Sessions", color = Color(0xFF8B949E), fontSize = 10.sp)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF30363D), thickness = 1.dp)
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "SESSIONS (${sessions.size})",
                        color = Color(0xFF58A6FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        itemsIndexed(sessions) { idx, session ->
                            val isSelected = idx == activeIdx
                            Surface(
                                onClick = {
                                    BashShellSession.switchSession(idx)
                                    coroutineScope.launch { drawerState.close() }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF21262D),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (session.isRunning) Color(0xFF3FB950) else Color(0xFFF85149))
                                        )
                                        Column {
                                            Text(
                                                session.title,
                                                color = Color.White,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                session.cwd,
                                                color = if (isSelected) Color(0xFFE1D5F5) else Color(0xFF8B949E),
                                                fontSize = 9.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    if (sessions.size > 1) {
                                        Icon(
                                            Icons.Default.Close,
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
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFF30363D), thickness = 1.dp)
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val s = BashShellSession.createSession(context)
                            coroutineScope.launch { drawerState.close() }
                            Toast.makeText(context, "Session ${s.id} created", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ New Session", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = {
                            val s = BashShellSession.createSession(context, "${sessions.size + 1}: Host Shell")
                            s.startHost(context)
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC9D1D9)),
                        border = BorderStroke(1.dp, Color(0xFF30363D)),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("⚡ Fail-safe Host Shell", fontSize = 10.sp)
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top Bar: Drawer Toggle + Session Tabs + New Session + Google Login ─
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(Color(0xFF0D1117))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ☰ Side Drawer Button (Swipe or Tap)
                Surface(
                    onClick = { coroutineScope.launch { drawerState.open() } },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Sessions Menu", tint = Color(0xFF58A6FF), modifier = Modifier.size(12.dp))
                        Text("${sessions.size}", fontSize = 9.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                // Multi-Session Tabs (Compact)
                sessions.forEachIndexed { idx, session ->
                    val isSelected = idx == activeIdx
                    Surface(
                        onClick = { BashShellSession.switchSession(idx) },
                        shape = RoundedCornerShape(5.dp),
                        color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF21262D)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = session.title,
                                fontSize = 9.5.sp,
                                color = if (isSelected) Color.White else Color(0xFF8B949E),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (sessions.size > 1) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Tab",
                                    tint = if (isSelected) Color.White else Color(0xFF8B949E),
                                    modifier = Modifier
                                        .size(11.dp)
                                        .clickable { BashShellSession.closeSession(idx) }
                                )
                            }
                        }
                    }
                }

                // [+] New Session Button
                Surface(
                    onClick = {
                        val s = BashShellSession.createSession(context)
                        Toast.makeText(context, "Session ${s.id} created", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF238636)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                        Text("+ New", fontSize = 9.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                // 🔑 1-Tap Google Login Button (Relocated right beside New Session button!)
                Surface(
                    onClick = {
                        if (oauthUrl != null) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            BashShellSession.sendCommand("agy")
                        }
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = if (oauthUrl != null) Color(0xFF1A73E8) else if (isAuthNow) Color(0xFF1B5E20) else Color(0xFF21262D)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("🔑", fontSize = 9.sp)
                        Text(
                            text = if (oauthUrl != null) "Google Login" else if (isAuthNow) "Google (Active)" else "Google Login",
                            fontSize = 9.5.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // [📋 Copy Link] button (if OAuth URL present)
                if (oauthUrl != null) {
                    Surface(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(oauthUrl))
                            Toast.makeText(context, "Full URL Copied! 📋", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF0D47A1)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📋 Copy", fontSize = 9.sp, color = Color.White)
                        }
                    }
                }

                // [Restart] & [Host Shell] if active session has exited
                if (activeSession?.isRunning == false) {
                    Surface(
                        onClick = { activeSession.restart(context) },
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF1F6FEB)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = Color.White, modifier = Modifier.size(11.dp))
                            Text("Restart", fontSize = 9.5.sp, color = Color.White)
                        }
                    }

                    Surface(
                        onClick = { activeSession.startHost(context) },
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF30363D)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Host Shell", fontSize = 9.5.sp, color = Color.White)
                        }
                    }
                }
            }

            // ── In-App Ubuntu Linux Subsystem Installation Banner (When not installed) ──
            if (!isLinuxInstalled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color(0xFF6E40C9),
                    shape = RoundedCornerShape(6.dp),
                    onClick = onSetupLinux
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "📦 Ubuntu Subsystem (Install)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(fontSize = 10.sp)
                            )
                            Text(
                                "Ubuntu 22.04 LTS (Git, Python3, Pip, agy CLI)",
                                color = Color(0xFFE1D5F5),
                                style = TextStyle(fontSize = 9.sp)
                            )
                        }
                        Button(
                            onClick = onSetupLinux,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                            shape = RoundedCornerShape(5.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("📥 Install", color = Color.White, style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // ── 1-Tap Auth Code Paste Helper (Compact 1-line strip) ───────────
            val isWaitingAuthCode = remember(shellLogs.size, refreshTrigger) {
                val recent = shellLogs.takeLast(10).joinToString("\n")
                recent.contains("authorization code", ignoreCase = true) ||
                (recent.contains("paste", ignoreCase = true) && recent.contains("code", ignoreCase = true))
            }

            if (isWaitingAuthCode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color(0xFF261C38),
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, Color(0xFF8957E5))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "📋 Code Copy Kar Liya?",
                            color = Color(0xFFD2A8FF),
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(fontSize = 10.sp)
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
                            shape = RoundedCornerShape(5.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("📋 1-Tap Paste & Connect", color = Color.White, style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // ── Real Terminal Emulator Viewport / Live Installer ──────────────
            if (BashShellSession.isInstallingLinux) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0D1117))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF30363D))
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "🐧 In-App Ubuntu Linux 22.04 Setup",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(fontSize = 11.sp)
                            )
                            Text(
                                text = BashShellSession.installStatusMessage,
                                color = Color(0xFF58A6FF),
                                style = TextStyle(fontSize = 9.5.sp)
                            )
                            if (BashShellSession.installProgress > 0) {
                                LinearProgressIndicator(
                                    progress = { BashShellSession.installProgress / 100f },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = Color(0xFF238636),
                                    trackColor = Color(0xFF21262D)
                                )
                                Text(
                                    text = "Progress: ${BashShellSession.installProgress}%",
                                    color = Color(0xFF8B949E),
                                    style = TextStyle(fontSize = 9.sp)
                                )
                            }
                        }
                    }

                    val installerListState = rememberLazyListState()
                    LaunchedEffect(BashShellSession.installLogs.size) {
                        if (BashShellSession.installLogs.isNotEmpty()) {
                            installerListState.animateScrollToItem(BashShellSession.installLogs.lastIndex)
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        color = Color(0xFF0D1117),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFF21262D))
                    ) {
                        LazyColumn(
                            state = installerListState,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            items(BashShellSession.installLogs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = when {
                                        logLine.startsWith("✅") || logLine.startsWith("🎉") -> Color(0xFF3FB950)
                                        logLine.startsWith("❌") || logLine.startsWith("⚠️") -> Color(0xFFF85149)
                                        logLine.startsWith("📦") || logLine.startsWith("⏳") || logLine.startsWith("🐧") -> Color(0xFF58A6FF)
                                        else -> Color(0xFFC9D1D9)
                                    },
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0D1117))
                ) {
                    val session = activeSession
                    if (session != null) {
                        XtermView(
                            session = session.ptySession,
                            jsInterface = session.ensureJsInterface(context),
                            modifier = Modifier.fillMaxSize(),
                            refreshTrigger = refreshTrigger
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No active terminal session", color = Color(0xFF8B949E), style = TextStyle(fontSize = 11.sp))
                        }
                    }
                }
            }

            // ── Termux-Style Interactive Menu Bar (Compact UI scaling) ───────
            val activeModelName = remember(shellLogs.size, showModelDialog, refreshTrigger) { LinuxSubsystem.getSelectedModel(context) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ⚡ Commands Menu
                Surface(
                    onClick = { showCommandsDialog = true },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("⚡", fontSize = 10.sp)
                        Text("Commands", color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 🧠 Model Menu
                Surface(
                    onClick = { showModelDialog = true },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("🧠", fontSize = 10.sp)
                        val shortModel = when {
                            activeModelName.contains("3.8", true) -> "3.8 Flash"
                            activeModelName.contains("2.5", true) -> "2.5 Pro"
                            activeModelName.contains("2.0", true) -> "2.0 Flash"
                            activeModelName.contains("Claude", true) -> "Claude"
                            else -> "Model"
                        }
                        Text("Model ($shortModel)", color = Color(0xFFD29922), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // ⚙️ Settings Menu
                Surface(
                    onClick = { showSettingsDialog = true },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("⚙️", fontSize = 10.sp)
                        Text("Settings", color = Color(0xFF3FB950), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 🚀 Run server.py
                if (isLinuxInstalled) {
                    Surface(
                        onClick = {
                            LinuxSubsystem.deployServerPy(context)
                            onSend("python3 /storage/Download/server.py")
                        },
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF238636)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("🚀", fontSize = 9.5.sp)
                            Text("server.py", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 🤖 Quick Run agy
                Surface(
                    onClick = { onSend("agy") },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF1F6FEB)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("🤖", fontSize = 9.5.sp)
                        Text("agy", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 🔄 Quick Resume
                Surface(
                    onClick = { onSend("agy --continue") },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("🔄", fontSize = 9.5.sp)
                        Text("Resume", color = Color(0xFFC9D1D9), fontSize = 10.sp)
                    }
                }

                // 🧹 Clear
                Surface(
                    onClick = { onSend("clear") },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D)
                ) {
                    Text("🧹 Clear", color = Color(0xFF8B949E), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }

                // 🟢 Status Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAuthNow) Color(0xFF1A3B1A) else Color(0xFF3B1A1A)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (isAuthNow) Color(0xFF3FB950) else Color(0xFFF85149)))
                        Text(if (isAuthNow) "Connected" else "Login", color = if (isAuthNow) Color(0xFF3FB950) else Color(0xFFF85149), fontSize = 9.sp)
                    }
                }

                // ➕ Add New Session Button at the very end of footer controls
                Surface(
                    onClick = {
                        val s = BashShellSession.createSession(context)
                        Toast.makeText(context, "Session ${s.id} created", Toast.LENGTH_SHORT).show()
                        activeSession?.refocus()
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF238636)
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Session", tint = Color.White, modifier = Modifier.size(11.dp))
                        Text("New Session", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Mobile key shortcuts (Termux-style) with + button at the end
            MobileKeyBar(
                onKey = { key ->
                    when (key) {
                        "CTRL+C" -> { BashShellSession.sendCtrlC(); activeSession?.refocus() }
                        "CTRL+D" -> { BashShellSession.sendCtrlD(); activeSession?.refocus() }
                        "CTRL+Z" -> { BashShellSession.sendCtrlZ(); activeSession?.refocus() }
                        "ESC"    -> { BashShellSession.sendRaw("\u001B"); activeSession?.refocus() }
                        "TAB"    -> { BashShellSession.sendRaw("\t"); activeSession?.refocus() }
                        "↑"      -> { BashShellSession.sendRaw("\u001B[A"); activeSession?.refocus() }
                        "↓"      -> { BashShellSession.sendRaw("\u001B[B"); activeSession?.refocus() }
                        "←"      -> { BashShellSession.sendRaw("\u001B[D"); activeSession?.refocus() }
                        "→"      -> { BashShellSession.sendRaw("\u001B[C"); activeSession?.refocus() }
                        "SPACE"  -> { BashShellSession.sendRaw(" "); activeSession?.refocus() }
                        "ENTER"  -> { BashShellSession.sendRaw("\r"); activeSession?.refocus() }
                        "clear"  -> { BashShellSession.clear(); activeSession?.refocus() }
                        "ls -la" -> { BashShellSession.sendCommand("ls -la"); activeSession?.refocus() }
                        else     -> { BashShellSession.sendRaw(key); activeSession?.refocus() }
                    }
                },
                onPaste = {
                    val clip = clipboardManager.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        activeSession?.pasteText(clip)
                        activeSession?.refocus()
                    } else {
                        Toast.makeText(context, "Clipboard empty!", Toast.LENGTH_SHORT).show()
                    }
                },
                onNewSession = {
                    val s = BashShellSession.createSession(context)
                    Toast.makeText(context, "Session ${s.id} created", Toast.LENGTH_SHORT).show()
                    activeSession?.refocus()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main TerminalBottomSheet (Lifecycle State-Restored & High Performance)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalBottomSheet(
    onDismiss: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val status by viewModel.status.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pid by viewModel.pid.collectAsState()
    val isLinuxInstalled by viewModel.isLinuxInstalled.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("⚡ Bridge Logs", "❯_ Bash Shell")

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isMinimized by remember { mutableStateOf(false) }

    // Lifecycle state management: refresh UI and repaint canvas immediately on ON_RESUME
    var lifecycleRefreshKey by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleRefreshKey++
                BashShellSession.activeSession?.refocus()
                BashShellSession.activeSession?.jsInterface?.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startShell(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161B22),
        dragHandle = {
            if (!isMinimized) {
                BottomSheetDefaults.DragHandle(color = Color(0xFF555555), modifier = Modifier.height(8.dp))
            }
        },
        modifier = if (isMinimized) {
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        } else {
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        }
    ) {
        Column(
            Modifier
                .then(
                    if (isMinimized) Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                    else Modifier
                        .fillMaxSize()
                )
                .imePadding()
                .navigationBarsPadding()
        ) {

            // ── Header: Status + Controls (Compact & Clickable when Minimized) ────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1117))
                    .clickable { if (isMinimized) isMinimized = false }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusBadge(status, pid)
                ControlButtons(
                    status = status,
                    isMinimized = isMinimized,
                    onToggleMinimize = { isMinimized = !isMinimized },
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

            if (!isMinimized) {
                // ── Bridge Info Row ────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1117))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "📍 ${TermuxBridgeService.state.activeUrl.value}",
                        style = TextStyle(fontSize = 8.5.sp, color = Color(0xFF29B6F6))
                    )
                    Text(
                        "📁 /storage/…/Download",
                        style = TextStyle(fontSize = 8.5.sp, color = Color(0xFF9E9E9E))
                    )
                }

                // ── Tab Bar (Compact Termux) ──────────────────────────────────
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
                                    style = TextStyle(
                                        fontSize = 9.5.sp,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedTab == index) Color(0xFF29B6F6) else Color(0xFF9E9E9E)
                                    )
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
                            isLinuxInstalled = isLinuxInstalled,
                            refreshTrigger = lifecycleRefreshKey
                        )
                    }
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
        shape = RoundedCornerShape(16.dp),
        color = animBg,
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = TextStyle(fontSize = 10.sp, color = Color.White))
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Open Terminal",
                modifier = Modifier.size(12.dp),
                tint = Color.White
            )
        }
    }
}
