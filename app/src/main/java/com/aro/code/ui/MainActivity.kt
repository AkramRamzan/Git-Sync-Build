package com.aro.code.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aro.code.terminal.TermuxBridgeService
import com.aro.code.ui.navigation.AroCodeNavGraph
import com.aro.code.ui.theme.AroCodeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AroCodeTheme {
                AroCodeNavGraph()
            }
        }
        // Auto-start the Anti-Gravity Bridge Server in background
        startForegroundService(
            Intent(this, TermuxBridgeService::class.java).apply {
                action = TermuxBridgeService.ACTION_START
            }
        )
    }
}
