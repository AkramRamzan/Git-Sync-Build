package com.aro.code.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF82B1FF),
    onPrimary        = Color(0xFF003070),
    primaryContainer = Color(0xFF00458F),
    background       = Color(0xFF1A1C1E),
    surface          = Color(0xFF1A1C1E),
    surfaceVariant   = Color(0xFF252830)
)

@Composable
fun AroCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}

data class Spacing(val small: Dp = 8.dp, val medium: Dp = 16.dp, val large: Dp = 24.dp)
val MaterialTheme.spacing: Spacing get() = Spacing()
