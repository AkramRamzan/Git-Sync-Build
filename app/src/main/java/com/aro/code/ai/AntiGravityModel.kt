package com.aro.code.ai

/**
 * 2026 Specialized Autonomous Agent Models for Anti-Gravity CLI v2.0.
 * Matches Termux CLI (agy) model profiles.
 */
enum class AntiGravityModel(
    val id: String,
    val displayName: String,
    val description: String,
    val fallbackId: String = "gemini-2.5-flash"
) {
    FLASH_2_5(
        id = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash (Fast Execution) ⚡",
        description = "High-speed agent execution, live code inspection & tools",
        fallbackId = "gemini-2.5-flash"
    ),
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash (High) 🚀",
        description = "Frontier preview agent model for complex autonomous tasks",
        fallbackId = "gemini-2.5-flash"
    ),
    THINKING_2_5(
        id = "gemini-2.5-pro",
        displayName = "Gemini 2.5 Pro (Thinking) 🧠",
        description = "Deep reasoning & complex multi-file architectural synthesis",
        fallbackId = "gemini-2.5-flash"
    );

    companion object {
        fun fromId(id: String?): AntiGravityModel {
            if (id.isNullOrBlank()) return FLASH_2_5
            val clean = id.removePrefix("models/").trim()
            if (clean.contains("1.5") || clean.contains("2.0")) {
                return FLASH_2_5
            }
            if (clean.contains("3.8")) {
                return FLASH_3_8
            }
            if (clean.contains("pro") || clean.contains("thinking")) {
                return THINKING_2_5
            }
            return entries.firstOrNull { it.id == clean } ?: FLASH_2_5
        }
    }
}
