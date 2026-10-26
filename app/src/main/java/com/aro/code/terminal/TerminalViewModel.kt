package com.aro.code.terminal

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Mini Linux installation status */
    private val _isLinuxInstalled = MutableStateFlow(false)
    val isLinuxInstalled: StateFlow<Boolean> = _isLinuxInstalled.asStateFlow()

    /** Current working directory of the shell session */
    val currentWorkingDirectory: String get() = BashShellSession.cwd

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

    fun sendCtrlC() {
        viewModelScope.launch {
            BashShellSession.sendCtrlC()
        }
    }

    fun createSession(context: Context, title: String? = null) {
        viewModelScope.launch {
            BashShellSession.createSession(context, title)
        }
    }

    fun closeSession(index: Int) {
        viewModelScope.launch {
            BashShellSession.closeSession(index)
        }
    }

    fun switchSession(index: Int) {
        BashShellSession.switchSession(index)
    }

    fun startShell(context: Context? = null) {
        if (context != null) {
            _isLinuxInstalled.value = LinuxSubsystem.isInstalled(context)
        }
        viewModelScope.launch { BashShellSession.start(context) }
    }

    fun installMiniLinux(context: Context) {
        viewModelScope.launch {
            BashShellSession.installMiniLinux(context)
            // Poll with coroutine delay instead of blocking while-loop on main thread
            while (BashShellSession.isInstallingLinux) {
                kotlinx.coroutines.delay(300)
            }
            _isLinuxInstalled.value = LinuxSubsystem.isInstalled(context)
        }
    }

    fun isLinuxInstalled(context: Context): Boolean {
        val installed = LinuxSubsystem.isInstalled(context)
        _isLinuxInstalled.value = installed
        return installed
    }

    fun stopShell() {
        viewModelScope.launch { BashShellSession.stop() }
    }

    override fun onCleared() {
        super.onCleared()
        // Do not kill persistent background shell sessions when ViewModel clears on screen navigation.
        // Sessions survive so user can switch between Editor/Chat and Terminal seamlessly.
    }
}
