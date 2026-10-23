package com.aro.code.ai

/**
 * 2026 Active Gemini Models for Aro Code.
 * Deprecated 1.5 and 2.0 models are automatically redirected to 2.5 Flash.
 */
enum class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
) {
    FLASH_2_5(
        id = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash ⚡ (Recommended)",
        description = "Latest 2026 high-speed, stable & production coding model"
    ),
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash 🚀",
        description = "Frontier preview model for autonomous workflows"
    ),
    PRO_2_5(
        id = "gemini-2.5-pro",
        displayName = "Gemini 2.5 Pro 🧠 (Thinking)",
        description = "Advanced reasoning, complex architecture & deep thinking"
    );

    companion object {
        fun fromId(id: String?): GeminiModel {
            if (id.isNullOrBlank()) return FLASH_2_5
            val clean = id.removePrefix("models/").trim()
            // Auto-redirect deprecated 1.5 or 2.0 models to 2026 default
            if (clean.contains("1.5") || clean.contains("2.0-flash")) {
                return FLASH_2_5
            }
            if (clean.contains("3.8-pro") || clean.contains("2.5-pro") || clean.contains("pro")) {
                return PRO_2_5
            }
            return entries.firstOrNull { it.id == clean } ?: FLASH_2_5
        }
    }
}
