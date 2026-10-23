package com.aro.code.ai

enum class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
) {
    FLASH_3_8(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash ⚡",
        description = "Latest flagship model for speed, intelligence & code generation"
    ),
    FLASH_LITE_3_8(
        id = "gemini-3.8-flash-lite",
        displayName = "Gemini 3.8 Flash Lite 🪶",
        description = "Ultra-fast low-latency lightweight model"
    ),
    PRO_3_8(
        id = "gemini-3.8-pro",
        displayName = "Gemini 3.8 Pro 🧠",
        description = "Deep reasoning, complex logic & architecture"
    ),
    FLASH_1_5(
        id = "gemini-1.5-flash",
        displayName = "Gemini 1.5 Flash ⚡",
        description = "High-speed balanced coding model"
    ),
    PRO_1_5(
        id = "gemini-1.5-pro",
        displayName = "Gemini 1.5 Pro 🧠",
        description = "Standard advanced reasoning model"
    );

    companion object {
        fun fromId(id: String?): GeminiModel {
            if (id.isNullOrBlank() || id.contains("2.0") || id.contains("2.5")) {
                return FLASH_3_8
            }
            return entries.firstOrNull { it.id == id } ?: FLASH_3_8
        }
    }
}
