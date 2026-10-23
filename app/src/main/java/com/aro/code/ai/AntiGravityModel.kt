package com.aro.code.ai

/**
 * 2026 Specialized Autonomous Agent Models for Anti-Gravity CLI v2.0.
 * Matches Termux CLI (agy) model profiles powered by Gemini 3.8.
 */
enum class AntiGravityModel(
    val id: String,
    val displayName: String,
    val description: String,
    val fallbackId: String = "gemini-3.8-flash"
) {
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash (High) ⚡",
        description = "High-speed agent execution, live code inspection & tool calling",
        fallbackId = "gemini-3.8-flash"
    ),
    THINKING_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 (Thinking) 🧠",
        description = "Deep reasoning & complex multi-file architectural synthesis",
        fallbackId = "gemini-3.8-flash"
    );

    companion object {
        fun fromId(id: String?): AntiGravityModel {
            if (id.isNullOrBlank()) return FLASH_3_8
            val clean = id.removePrefix("models/").trim()
            if (clean.contains("1.5") || clean.contains("2.0") || clean.contains("2.5")) {
                return FLASH_3_8
            }
            if (clean.contains("thinking") || clean.contains("pro")) {
                return THINKING_3_8
            }
            return entries.firstOrNull { it.id == clean } ?: FLASH_3_8
        }
    }
}
