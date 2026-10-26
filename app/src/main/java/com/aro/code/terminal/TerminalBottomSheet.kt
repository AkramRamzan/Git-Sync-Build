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
// OAuth URL Extractor from Terminal / Shell Logs
// ─────────────────────────────────────────────────────────────────────────────

private fun extractOAuthUrl(logs: List<String>): String? {
    val urlRegex = Regex("https://accounts\\.google\\.com/o/oauth2/auth\\?[A-Za-z0-9_.~%!$&'()*+,;=:@/?#\\-]+")
    return logs.asReversed().firstNotNullOfOrNull { line ->
        urlRegex.find(line)?.value
    } ?: run {
        val allText = logs.joinToString("\n")
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

// ─────────────────────────────────────────────────────────────────────────────
// Unified Terminal Top Bar (Termux Mobile Logo, Clear, Setting, Resume, Google Key + Dot)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TerminalTopBar(
    isMinimized: Boolean,
    onToggleMinimize: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenTermux: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
    onResume: () -> Unit,
    onGoogleLogin: () -> Unit,
    isAuthNow: Boolean,
    oauthUrl: String?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side: ☰ Drawer Menu + Termux Mobile Phone Icon + Storage Download Path
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ☰ Sessions Menu
            Surface(
                onClick = onOpenDrawer,
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF21262D),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Menu, contentDescription = "Sessions", tint = Color(0xFF58A6FF), modifier = Modifier.size(12.dp))
                }
            }

            // 📱 1-tap Open Termux button (Mobile phone icon)
            Surface(
                onClick = onOpenTermux,
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1F6FEB),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = "Termux", tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }

            // Storage / Download path next to Termux
            Text(
                "📁 /storage/…/Download",
                style = TextStyle(fontSize = 7.5.sp, color = Color(0xFF8B949E)),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }

        // Right side: Clear (🧹), Setting (⚙️), Resume (🔄), Google Login (🔑 + dot), Minimize (－)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 🧹 Clear Terminal / Logs
            Surface(
                onClick = onClear,
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF21262D),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🧹", fontSize = 8.5.sp)
                }
            }

            // ⚙️ Settings & Model Selector
            Surface(
                onClick = onSettings,
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF21262D),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("⚙️", fontSize = 8.5.sp)
                }
            }

            // 🔄 Quick Resume (agy --continue)
            Surface(
                onClick = onResume,
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF21262D),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🔄", fontSize = 8.5.sp)
                }
            }

            // 🔑 Google Login (Key icon + Active Green / Inactive Red dot — NO "Google" text)
            Surface(
                onClick = onGoogleLogin,
                shape = RoundedCornerShape(4.dp),
                color = if (oauthUrl != null) Color(0xFF1A73E8) else if (isAuthNow) Color(0xFF1B5E20) else Color(0xFF21262D),
                modifier = Modifier.height(22.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("🔑", fontSize = 8.5.sp)
                    // Status dot: Green when active, Red when inactive
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isAuthNow) Color(0xFF3FB950) else Color(0xFFF85149))
                    )
                }
            }

            // Minimize / Restore Toggle Button (-)
            Surface(
                onClick = onToggleMinimize,
                shape = RoundedCornerShape(4.dp),
                color = if (isMinimized) Color(0xFF1F6FEB) else Color(0xFF21262D),
                border = BorderStroke(1.dp, if (isMinimized) Color(0xFF58A6FF) else Color(0xFF30363D)),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isMinimized) "＋" else "－",
                        color = if (isMinimized) Color.White else Color(0xFF58A6FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Unified Terminal Settings & AI Model Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TerminalSettingsDialog(
    isLinuxInstalled: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isAuth = remember { LinuxSubsystem.isLoggedIn(context) }
    val models = listOf(
        "Gemini 3.8 Flash (High)" to "Gemini 3.8 Flash (High) ⚡",
        "gemini-2.5-pro"          to "Gemini 2.5 Pro 🧠",
        "gemini-2.5-flash"        to "Gemini 2.5 Flash 🚀",
        "gemini-2.0-flash"        to "Gemini 2.0 Flash",
        "Claude 3.5 Sonnet"       to "Claude 3.5 Sonnet 🎭"
    )
    val currentModel = remember { LinuxSubsystem.getSelectedModel(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚙️", fontSize = 9.sp)
                Text("Terminal Settings & AI Model", color = Color.White, fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 11.sp))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // AI Model Selection
                Text("AI Model Selection", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 9.5.sp))
                models.forEach { (modelId, modelName) ->
                    val isSelected = currentModel.contains(modelId, ignoreCase = true)
                    Surface(
                        onClick = {
                            LinuxSubsystem.updateSelectedModel(context, modelId)
                            BashShellSession.activeSession?.addLog("🧠 [System] Model set to: $modelName")
                            onDismiss()
                        },
                        shape = RoundedCornerShape(5.dp),
                        color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF21262D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(modelName, color = Color.White, style = TextStyle(fontSize = 9.5.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal))
                            if (isSelected) Text("✔", color = Color.White, fontSize = 9.5.sp)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Subsystem Status", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 9.5.sp))
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF0D1117),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Ubuntu 22.04 LTS: ${if (isLinuxInstalled) "✅ Installed" else "❌ Not Installed"}", color = Color.White, style = TextStyle(fontSize = 9.sp))
                        Text("Google OAuth: ${if (isAuth) "✅ Logged in" else "⚠️ Not Logged in"}", color = Color.White, style = TextStyle(fontSize = 9.sp))
                        Text("Active Sessions: ${BashShellSession.sessions.size}", color = Color.White, style = TextStyle(fontSize = 9.sp))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Actions", color = Color(0xFFD29922), fontWeight = FontWeight.Bold, style = TextStyle(fontSize = 9.5.sp))

                Surface(
                    onClick = {
                        LinuxSubsystem.resetAuth(context)
                        Toast.makeText(context, "Logged out from Antigravity CLI", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF3E1F1F),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🚪 Logout Google Session", color = Color(0xFFFF7B72), style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold))
                    }
                }

                Surface(
                    onClick = {
                        BashShellSession.activeSession?.restart(context)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF21262D),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔄 Restart Active Session", color = Color.White, style = TextStyle(fontSize = 9.5.sp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF8B949E), style = TextStyle(fontSize = 9.5.sp))
            }
        },
        containerColor = Color(0xFF161B22)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Mobile Keyboard Shortcuts Bar (Termux-style compact single row)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MobileKeyBar(
    onKey: (String) -> Unit
) {
    var isCtrlSticky by remember { mutableStateOf(false) }
    var isAltSticky by remember { mutableStateOf(false) }

    // Navigation & Control (Termux style with Arrow keys, sticky CTRL/ALT)
    val navKeys = listOf("ESC", "TAB", "CTRL", "ALT", "CTRL+C", "CTRL+D", "↑", "↓", "←", "→", "ALT+F5", "ALT+F6")

    Surface(
        color = Color(0xFF0D1117),
        modifier = Modifier.fillMaxWidth().height(25.dp)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navKeys.forEach { key ->
                val isArrow = key == "↑" || key == "↓" || key == "←" || key == "→"
                val isCtrlC = key == "CTRL+C"
                val isCtrlD = key == "CTRL+D"
                val isCtrl = key == "CTRL"
                val isAlt = key == "ALT"
                val isAccent = key == "ESC" || key == "TAB"
                val isHighlighted = (isCtrl && isCtrlSticky) || (isAlt && isAltSticky)

                Surface(
                    onClick = {
                        when (key) {
                            "CTRL" -> isCtrlSticky = !isCtrlSticky
                            "ALT"  -> isAltSticky = !isAltSticky
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
                    shape = RoundedCornerShape(4.dp),
                    color = when {
                        isHighlighted -> Color(0xFF388BFD)
                        isArrow -> Color(0xFF1F6FEB)
                        isCtrlC || isCtrlD -> Color(0xFF4E1010)
                        isCtrl || isAlt -> Color(0xFF21262D)
                        isAccent -> Color(0xFF21262D)
                        else -> Color(0xFF21262D)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isHighlighted -> Color(0xFF58A6FF)
                            isArrow -> Color(0xFF388BFD)
                            else -> Color(0xFF30363D)
                        }
                    ),
                    modifier = Modifier.height(21.dp)
                ) {
                    Box(
                        Modifier.padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = key,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                fontWeight = if (isArrow || isHighlighted) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = when {
                                isHighlighted || isArrow -> Color.White
                                isCtrlC || isCtrlD -> Color(0xFFFF7B72)
                                isAccent -> Color(0xFF58A6FF)
                                else -> Color(0xFFC9D1D9)
                            }
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
    refreshTrigger: Int = 0,
    drawerState: DrawerState,
    oauthUrl: String?
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val activeSession = BashShellSession.activeSession
    val shellLogs = activeSession?.logs ?: remember(logs) { logs.filter { it.startsWith("💻") } }

    LaunchedEffect(Unit) {
        BashShellSession.start(context)
    }

    val sessions = BashShellSession.sessions
    val activeIdx = BashShellSession.activeSessionIndex

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

            // [📋 Copy Link] button (if OAuth URL present)
            if (oauthUrl != null) {
                Surface(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(oauthUrl))
                        Toast.makeText(context, "Full URL Copied! 📋", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF0D47A1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🔑 Google OAuth Link Ready", fontSize = 8.5.sp, color = Color.White)
                        Text("📋 Copy", fontSize = 8.5.sp, color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // [Restart] & [Host Shell] if active session has exited
            if (activeSession?.isRunning == false) {
                Surface(
                    onClick = { activeSession.restart(context) },
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF1F6FEB),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = Color.White, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Session Exited — Tap to Restart", fontSize = 8.5.sp, color = Color.White)
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
                        key(session.id) {
                            XtermView(
                                session = session,
                                modifier = Modifier.fillMaxSize(),
                                refreshTrigger = refreshTrigger
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No active terminal session", color = Color(0xFF8B949E), style = TextStyle(fontSize = 11.sp))
                        }
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
                        "ALT+F5" -> { BashShellSession.sendRaw("\u001B[15~"); activeSession?.refocus() }
                        "ALT+F6" -> { BashShellSession.sendRaw("\u001B[17~"); activeSession?.refocus() }
                        else     -> { BashShellSession.sendRaw(key); activeSession?.refocus() }
                    }
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

    var selectedTab by remember { mutableIntStateOf(1) }
    val tabs = listOf("⚡ Bridge Logs", "❯_ Bash Shell")

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            BashShellSession.activeSession?.refocus()
            BashShellSession.activeSession?.jsInterface?.refresh()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isMinimized by remember { mutableStateOf(false) }

    // Lifecycle state management: refresh UI and repaint canvas immediately on ON_RESUME
    var lifecycleRefreshKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showSettingsDialog by remember { mutableStateOf(false) }

    val activeSession = BashShellSession.activeSession
    val shellLogs = activeSession?.logs ?: remember(logs) { logs.filter { it.startsWith("💻") } }
    val isAuthNow = remember(shellLogs.size, lifecycleRefreshKey) {
        LinuxSubsystem.isLoggedIn(context)
    }
    val oauthUrl = remember(shellLogs.size, activeSession?.id, lifecycleRefreshKey) {
        extractOAuthUrl(shellLogs)
    }

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

            // ── Top Bar: Termux Logo, Clear, Setting, Resume, Google Key+Dot, Minimize ────
            TerminalTopBar(
                isMinimized = isMinimized,
                onToggleMinimize = { isMinimized = !isMinimized },
                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                onOpenTermux = { viewModel.openTermux(context) },
                onClear = {
                    BashShellSession.clear()
                    viewModel.clearLogs()
                },
                onSettings = { showSettingsDialog = true },
                onResume = { BashShellSession.sendCommand("agy --continue") },
                onGoogleLogin = {
                    if (oauthUrl != null) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: " + e.message, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        BashShellSession.sendCommand("agy")
                    }
                },
                isAuthNow = isAuthNow,
                oauthUrl = oauthUrl
            )

            if (!isMinimized) {
                // ── Compact Tab Bar ──────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1117))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Surface(
                            onClick = { selectedTab = index },
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF161B22),
                            border = BorderStroke(1.dp, if (isSelected) Color(0xFF388BFD) else Color(0xFF30363D)),
                            modifier = Modifier.height(20.dp)
                        ) {
                            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    title,
                                    style = TextStyle(
                                        fontSize = 8.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else Color(0xFF8B949E)
                                    )
                                )
                            }
                        }
                    }
                }

                // ── Tab Content ───────────────────────────────────────────────
                // Keep BOTH tabs mounted at all times to prevent WebView destroy/recreate
                // which causes screen blinking and terminal state loss on tab switch.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // BashShellTab always mounted — only visibility toggled
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (selectedTab == 1) Modifier
                                else Modifier.size(0.dp)
                            )
                    ) {
                        BashShellTab(
                            logs = logs,
                            onSend = { viewModel.sendBashCommand(it) },
                            onSetupLinux = { viewModel.installMiniLinux(context) },
                            isLinuxInstalled = isLinuxInstalled,
                            refreshTrigger = lifecycleRefreshKey,
                            drawerState = drawerState,
                            oauthUrl = oauthUrl
                        )
                    }
                    // BridgeLogsTab overlaid when Tab 0 selected
                    if (selectedTab == 0) {
                        Box(Modifier.fillMaxSize()) {
                            BridgeLogsTab(logs)
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        TerminalSettingsDialog(
            isLinuxInstalled = isLinuxInstalled,
            onDismiss = { showSettingsDialog = false }
        )
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
