package com.aro.code.ai

enum class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
) {
    FLASH_2_0(
        id = "gemini-2.0-flash",
        displayName = "Gemini 2.0 Flash ⚡",
        description = "Next-gen speed and multimodal capabilities"
    ),
    FLASH_2_0_LITE(
        id = "gemini-2.0-flash-lite",
        displayName = "Gemini 2.0 Flash Lite 🪶",
        description = "Lightweight, ultra-fast latency"
    ),
    PRO_1_5(
        id = "gemini-1.5-pro",
        displayName = "Gemini 1.5 Pro 🧠",
        description = "Complex reasoning and code generation"
    ),
    FLASH_1_5(
        id = "gemini-1.5-flash",
        displayName = "Gemini 1.5 Flash ⚡",
        description = "Balanced performance for general coding"
    ),
    PRO_EXP(
        id = "gemini-2.5-pro-exp-03-25",
        displayName = "Gemini 2.5 Pro Exp 🔬",
        description = "Experimental reasoning model"
    );

    companion object {
        fun fromId(id: String?): GeminiModel =
            entries.firstOrNull { it.id == id } ?: FLASH_2_0
    }
}
