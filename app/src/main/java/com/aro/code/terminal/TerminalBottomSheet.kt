package com.aro.code.terminal

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    line.contains("✅") || line.contains("RUNNING") || line.contains("started") ->
        Color(0xFF4CAF50)
    line.contains("❌") || line.contains("ERROR") || line.contains("error", true) ->
        Color(0xFFEF5350)
    line.contains("[Termux Bridge]") || line.contains("INFO") ->
        Color(0xFF29B6F6)
    line.contains("⚠") || line.contains("WARN") ->
        Color(0xFFFFA726)
    line.contains("💻") || line.contains("\$") ->
        Color(0xFFCE93D8)
    else -> Color(0xFFE0E0E0)
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
// Mobile Keyboard Shortcuts Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MobileKeyBar(onKey: (String) -> Unit) {
    val keys = listOf("ESC", "TAB", "CTRL+C", "↑", "↓", "-", "/", "~", "|", "&&", "clear", "ls -la")
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEach { key ->
            val isAccent = key == "CTRL+C" || key == "ESC" || key == "TAB"
            Surface(
                onClick = { onKey(key) },
                shape = RoundedCornerShape(6.dp),
                color = if (key == "CTRL+C") Color(0xFF4E1010) else if (isAccent) Color(0xFF21262D) else Color(0xFF1B2028),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D)),
                modifier = Modifier.height(30.dp)
            ) {
                Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = key,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = if (isAccent) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (key == "CTRL+C") Color(0xFFFF7B72) else if (isAccent) Color(0xFF58A6FF) else Color(0xFFC9D1D9)
                    )
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
    var showAgyDialog by remember { mutableStateOf(false) }
    var agyPromptInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    if (showAgyDialog) {
        AlertDialog(
            onDismissRequest = { showAgyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🤖", fontSize = 22.sp)
                    Text("Antigravity CLI Agent", style = MaterialTheme.typography.titleMedium, color = Color.White)
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
                        showAgyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                ) {
                    Text("Run agy 🚀", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgyDialog = false }) {
                    Text("Cancel", color = Color(0xFF8B949E))
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
            if (history.isEmpty() || history.last() != trimmed) {
                history.add(trimmed)
            }
            historyIndex = -1
            onSend(trimmed)
            input = ""
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

        // ── 1-Tap Google OAuth Login Button (Direct Chrome Launch) ─────────
        val oauthUrl = remember(shellLogs.size) {
            shellLogs.asReversed().firstOrNull { it.contains("https://accounts.google.com/o/oauth2/auth") }?.let { line ->
                val start = line.indexOf("https://accounts.google.com/o/oauth2/auth")
                line.substring(start).substringBefore(" ").substringBefore("\n").substringBefore("\r")
            }
        }
        if (oauthUrl != null) {
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
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "🔑 1-Tap Google Login (Open in Chrome)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Output area
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
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = logLineColor(line)
                    )
                )
            }
        }

        // VS Code Quick Action Chips
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF161B22))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isLinuxInstalled) {
                // 🤖 1-Tap agy AI Prompt runner
                Surface(
                    onClick = { showAgyDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1F6FEB)
                ) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("🤖", fontSize = 12.sp)
                        Text(
                            text = "agy Prompt",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!isLinuxInstalled) {
                Surface(
                    onClick = onSetupLinux,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6E40C9)
                ) {
                    Text(
                        text = "📦 Setup Ubuntu Linux (Full)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                Surface(
                    onClick = {
                        // Always deploy latest bundled server.py before running
                        LinuxSubsystem.deployServerPy(context)
                        submitCommand("python3 /storage/Download/server.py")
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF238636)
                ) {
                    Text(
                        text = "🚀 Run server.py",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    onClick = onSetupLinux,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6E40C9)
                ) {
                    Text(
                        text = "🔄 Reinstall Ubuntu",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Termux allow-external-apps config shortcut
            val context = LocalContext.current
            Surface(
                onClick = { TermuxBridgeService.openTermuxSetup(context) },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1F6FEB)
            ) {
                Text(
                    text = "⚙️ Fix Termux Config",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            val quickActions = listOf(
                "📦 pip install reqs" to "pip install fastapi uvicorn pydantic sse-starlette",
                "📂 ls -la" to "ls -la",
                "📍 pwd" to "pwd",
                "🔍 git status" to "git status",
                "🧹 clear" to "clear"
            )
            quickActions.forEach { (label, cmd) ->
                Surface(
                    onClick = { submitCommand(cmd) },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF21262D)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF58A6FF),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Mobile key shortcuts
        MobileKeyBar { key ->
            when (key) {
                "CTRL+C" -> {
                    BashShellSession.sendCtrlC()
                }
                "ESC" -> {
                    input = ""
                }
                "TAB" -> {
                    if (input.isNotBlank()) {
                        submitCommand(input)
                    } else {
                        submitCommand("ls")
                    }
                }
                "clear" -> {
                    submitCommand("clear")
                }
                "ls -la" -> {
                    submitCommand("ls -la")
                }
                "↑" -> {
                    if (history.isNotEmpty()) {
                        historyIndex = (historyIndex + 1).coerceAtMost(history.lastIndex)
                        input = history[history.lastIndex - historyIndex]
                    }
                }
                "↓" -> {
                    if (historyIndex > 0) {
                        historyIndex--
                        input = history[history.lastIndex - historyIndex]
                    } else if (historyIndex == 0) {
                        historyIndex = -1
                        input = ""
                    }
                }
                else -> input += key
            }
        }

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
