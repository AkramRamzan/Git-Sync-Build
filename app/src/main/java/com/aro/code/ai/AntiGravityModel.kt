package com.aro.code.ai

/**
 * 2026 Frontier Models for Anti-Gravity CLI v2.0 & Termux Bridge.
 * Directly corresponds to official `agy` model profiles.
 */
enum class AntiGravityModel(
    val id: String,
    val displayName: String,
    val description: String,
    val isExternalBridgeOnly: Boolean = false,
    val fallbackId: String = "gemini-3.8-flash"
) {
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash ⚡",
        description = "High-speed agent execution, live code inspection & tool calling",
        isExternalBridgeOnly = false,
        fallbackId = "gemini-3.8-flash"
    ),
    CLAUDE_SONNET_4_6(
        id = "claude-sonnet-4.6",
        displayName = "Claude Sonnet 4.6 (Thinking) 🧠",
        description = "Deep code reasoning, architectural synthesis & autonomous refactoring",
        isExternalBridgeOnly = true,
        fallbackId = "gemini-3.8-flash"
    ),
    CLAUDE_OPUS_4_6(
        id = "claude-opus-4.6",
        displayName = "Claude Opus 4.6 (Thinking) 🚀",
        description = "Frontier multi-step autonomous coding & deep system design",
        isExternalBridgeOnly = true,
        fallbackId = "gemini-3.8-flash"
    ),
    GPT_OSS_120B(
        id = "gpt-oss-120b",
        displayName = "GPT-OSS 120B (Medium) 🤖",
        description = "High-capacity open-weight coding and reasoning engine",
        isExternalBridgeOnly = true,
        fallbackId = "gemini-3.8-flash"
    ),
    GEMINI_4_ARGON(
        id = "gemini-4-argon",
        displayName = "Gemini 4 Argon 🔬",
        description = "Experimental frontier research model for complex algorithmic synthesis",
        isExternalBridgeOnly = true,
        fallbackId = "gemini-3.8-flash"
    );

    companion object {
        fun fromId(id: String?): AntiGravityModel {
            if (id.isNullOrBlank()) return FLASH_3_8
            val clean = id.removePrefix("models/").trim()
            if (clean.contains("sonnet", ignoreCase = true)) return CLAUDE_SONNET_4_6
            if (clean.contains("opus", ignoreCase = true)) return CLAUDE_OPUS_4_6
            if (clean.contains("oss", ignoreCase = true) || clean.contains("120b", ignoreCase = true)) return GPT_OSS_120B
            if (clean.contains("argon", ignoreCase = true)) return GEMINI_4_ARGON
            if (clean.contains("1.5") || clean.contains("2.0") || clean.contains("2.5")) {
                return FLASH_3_8
            }
            return entries.firstOrNull { it.id.equals(clean, ignoreCase = true) } ?: FLASH_3_8
        }
    }
}
