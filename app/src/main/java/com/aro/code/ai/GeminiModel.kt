package com.aro.code.ai

enum class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
) {
    FLASH_2_0(
        id = "gemini-2.0-flash",
        displayName = "Gemini 2.0 Flash ⚡",
        description = "Next-gen speed, multimodal & production ready"
    ),
    FLASH_2_0_LITE(
        id = "gemini-2.0-flash-lite",
        displayName = "Gemini 2.0 Flash Lite 🪶",
        description = "Lightweight with ultra-fast latency"
    ),
    FLASH_1_5(
        id = "gemini-1.5-flash",
        displayName = "Gemini 1.5 Flash ⚡",
        description = "High-speed and balanced for coding"
    ),
    PRO_1_5(
        id = "gemini-1.5-pro",
        displayName = "Gemini 1.5 Pro 🧠",
        description = "Complex reasoning and code generation"
    ),
    THINKING_2_0(
        id = "gemini-2.0-flash-thinking-exp-01-21",
        displayName = "Gemini 2.0 Thinking 💭",
        description = "Iterative reasoning and problem solving"
    );

    companion object {
        fun fromId(id: String?): GeminiModel {
            if (id.isNullOrBlank() || id.contains("gemini-2.5-pro-exp")) {
                return FLASH_2_0
            }
            return entries.firstOrNull { it.id == id } ?: FLASH_2_0
        }
    }
}
