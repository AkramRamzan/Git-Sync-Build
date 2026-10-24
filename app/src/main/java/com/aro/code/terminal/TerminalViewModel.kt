package com.aro.code.terminal

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt ViewModel that exposes [TermuxBridgeService.state] to Compose.
 * Handles Start / Stop / Restart actions and Bash shell commands.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor() : ViewModel() {

    /** Live status exposed as StateFlow for Compose collectors */
    val status: StateFlow<BridgeStatus> = TermuxBridgeService.state.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, BridgeStatus.STOPPED)

    /** Live log lines */
    val logs: StateFlow<List<String>> = TermuxBridgeService.state.logs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** PID of the running bridge process */
    val pid: StateFlow<Int?> = TermuxBridgeService.state.pid
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ──────────────────────────────────────────────────────────────
    // Bridge Controls
    // ──────────────────────────────────────────────────────────────

    fun startBridge(context: Context) {
        context.startForegroundService(
            Intent(context, TermuxBridgeService::class.java).apply {
                action = TermuxBridgeService.ACTION_START
            }
        )
    }

    fun stopBridge(context: Context) {
        context.startForegroundService(
            Intent(context, TermuxBridgeService::class.java).apply {
                action = TermuxBridgeService.ACTION_STOP
            }
        )
    }

    fun restartBridge(context: Context) {
        context.startForegroundService(
            Intent(context, TermuxBridgeService::class.java).apply {
                action = TermuxBridgeService.ACTION_RESTART
            }
        )
    }

    fun clearLogs() {
        TermuxBridgeService.state.clearLogs()
    }

    fun openTermux(context: Context) {
        TermuxBridgeService.openTermux(context)
    }

    // ──────────────────────────────────────────────────────────────
    // Bash Shell
    // ──────────────────────────────────────────────────────────────

    fun sendBashCommand(cmd: String) {
        viewModelScope.launch {
            BashShellSession.sendCommand(cmd.trim())
        }
    }

    fun startShell() {
        viewModelScope.launch { BashShellSession.start() }
    }

    fun stopShell() {
        viewModelScope.launch { BashShellSession.stop() }
    }

    override fun onCleared() {
        super.onCleared()
        BashShellSession.stop()
    }
}
