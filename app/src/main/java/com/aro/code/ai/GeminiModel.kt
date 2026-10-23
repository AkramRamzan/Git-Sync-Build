package com.aro.code.ai

enum class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
) {
    FLASH_1_5(
        id = "gemini-1.5-flash",
        displayName = "Gemini 1.5 Flash ⚡ (Recommended)",
        description = "High-speed, stable & globally supported across all API keys"
    ),
    PRO_1_5(
        id = "gemini-1.5-pro",
        displayName = "Gemini 1.5 Pro 🧠",
        description = "Advanced reasoning, large context & complex logic"
    ),
    FLASH_2_0(
        id = "gemini-2.0-flash",
        displayName = "Gemini 2.0 Flash 🚀",
        description = "Next-generation fast coding model"
    ),
    FLASH_2_5(
        id = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash ⚡",
        description = "Enhanced coding & multimodal speed"
    ),
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash (Preview)",
        description = "Frontier preview model for autonomous workflows"
    );

    companion object {
        fun fromId(id: String?): GeminiModel {
            if (id.isNullOrBlank()) return FLASH_1_5
            val clean = id.removePrefix("models/").trim()
            // Auto-redirect deprecated or non-existent pro variants to PRO_1_5
            if (clean.contains("3.8-pro") || clean.contains("2.5-pro")) {
                return PRO_1_5
            }
            return entries.firstOrNull { it.id == clean } ?: FLASH_1_5
        }
    }
}
