package com.aro.code.ai

/**
 * Specialized Autonomous Agent Models for Anti-Gravity CLI v2.0.
 * Matches Termux CLI (agy) model profiles.
 */
enum class AntiGravityModel(
    val id: String,
    val displayName: String,
    val description: String,
    val fallbackId: String = "gemini-1.5-flash"
) {
    THINKING_2_5(
        id = "gemini-2.5-pro",
        displayName = "Gemini 2.5 Pro (Thinking) 🧠",
        description = "Deep reasoning & autonomous multi-file architecture",
        fallbackId = "gemini-1.5-pro"
    ),
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash (High) ⚡",
        description = "High-speed agent execution & tool calling",
        fallbackId = "gemini-1.5-flash"
    ),
    CODE_ASSIST_AUTO(
        id = "gemini-1.5-pro",
        displayName = "Anti-Gravity Code Assist (Auto) 🚀",
        description = "Autonomous workspace architect for Android projects",
        fallbackId = "gemini-1.5-pro"
    );

    companion object {
        fun fromId(id: String?): AntiGravityModel {
            if (id.isNullOrBlank()) return THINKING_2_5
            val clean = id.removePrefix("models/").trim()
            return entries.firstOrNull { it.id == clean } ?: THINKING_2_5
        }
    }
}
