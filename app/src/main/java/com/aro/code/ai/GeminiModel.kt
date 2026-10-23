package com.aro.code.ai

/**
 * 2026 Active Gemini Models for Aro Code.
 * Deprecated 1.5, 2.0, and 2.5 models are automatically redirected to Gemini 3.8 Flash.
 */
enum class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
) {
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash ⚡ (Recommended)",
        description = "Latest 2026 high-speed, stable & active production coding model"
    ),
    FLASH_3_8_THINKING(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash (Thinking) 🧠",
        description = "Advanced reasoning, complex architecture & autonomous synthesis"
    );

    companion object {
        fun fromId(id: String?): GeminiModel {
            if (id.isNullOrBlank()) return FLASH_3_8
            val clean = id.removePrefix("models/").trim()
            // Auto-redirect deprecated 1.5, 2.0, or 2.5 models to 3.8 Flash
            if (clean.contains("1.5") || clean.contains("2.0") || clean.contains("2.5")) {
                return FLASH_3_8
            }
            if (clean.contains("thinking") || clean.contains("pro")) {
                return FLASH_3_8_THINKING
            }
            return entries.firstOrNull { it.id == clean } ?: FLASH_3_8
        }
    }
}
