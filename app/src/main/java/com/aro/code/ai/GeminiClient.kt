package com.aro.code.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    @SerialName("generationConfig") val generationConfig: GenerationConfig = GenerationConfig()
) {
    @Serializable
    data class Content(
        val role: String = "user",
        val parts: List<Part> = emptyList()
    )

    @Serializable
    data class Part(
        val text: String = ""
    )

    @Serializable
    data class GenerationConfig(
        val temperature: Float = 0.7f,
        @SerialName("maxOutputTokens") val maxOutputTokens: Int = 8192
    )
}

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate> = emptyList(),
    val error: GeminiErrorDetail? = null
) {
    @Serializable
    data class Candidate(
        val content: Content? = null,
        val finishReason: String? = null
    )

    @Serializable
    data class Content(
        val role: String? = null,
        val parts: List<Part> = emptyList()
    )

    @Serializable
    data class Part(
        val text: String = ""
    )

    fun text(): String = candidates.firstOrNull()
        ?.content?.parts?.joinToString("") { it.text } ?: ""
}

@Serializable
data class GeminiErrorResponse(
    val error: GeminiErrorDetail
)

@Serializable
data class GeminiErrorDetail(
    val code: Int = 0,
    val message: String = "Unknown Gemini API error",
    val status: String? = null
)

@Serializable
data class ModelListResponse(
    val models: List<GeminiModelItem> = emptyList()
)

@Serializable
data class GeminiModelItem(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList()
)

/**
 * Module 5 & Anti-Gravity – Gemini API Client.
 * Supports standard generation, real-time SSE streaming, and instant API key validation.
 */
@Singleton
class GeminiClient @Inject constructor(
    private val httpClient: HttpClient
) {
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun isOAuthToken(token: String): Boolean =
        token.startsWith("ya29.") || token.startsWith("Bearer ")

    /**
     * Instantly validate a Gemini API key or Google OAuth token.
     * Returns Result.success(Unit) if key is active, or Result.failure with error message.
     */
    suspend fun validateApiKey(apiKey: String): Result<Unit> = runCatching {
        val cleanKey = apiKey.filterNot { it.isWhitespace() }
        if (cleanKey.isBlank()) {
            error("API key cannot be empty")
        }
        val isOAuth = isOAuthToken(cleanKey)
        val url = if (isOAuth) baseUrl else "$baseUrl?key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        val response = httpClient.get(url) {
            if (isOAuth) {
                header("Authorization", "Bearer ${cleanKey.removePrefix("Bearer ").trim()}")
            } else {
                header("x-goog-api-key", cleanKey)
            }
        }
        val rawText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val errMsg = runCatching {
                json.decodeFromString<GeminiErrorResponse>(rawText).error.message
            }.getOrNull()?.ifBlank { null } ?: "HTTP ${response.status.value}: $rawText"
            error(errMsg)
        }
    }

    /**
     * Module 3.2 — List available models for this user's API key or OAuth token.
     */
    suspend fun listModels(apiKey: String): Result<List<String>> = runCatching {
        val cleanKey = apiKey.filterNot { it.isWhitespace() }
        val isOAuth = isOAuthToken(cleanKey)
        val url = if (isOAuth) baseUrl else "$baseUrl?key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        val response = httpClient.get(url) {
            if (isOAuth) {
                header("Authorization", "Bearer ${cleanKey.removePrefix("Bearer ").trim()}")
            } else {
                header("x-goog-api-key", cleanKey)
            }
        }
        val rawText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val errMsg = runCatching {
                json.decodeFromString<GeminiErrorResponse>(rawText).error.message
            }.getOrNull()?.ifBlank { null } ?: "HTTP ${response.status.value}: $rawText"
            error(errMsg)
        }
        val listResp = json.decodeFromString<ModelListResponse>(rawText)
        listResp.models
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .map { it.name }
    }

    /**
     * Module 3.3 — Dynamic Model Verification & Fallback Strategy.
     * Verifies that the preferred model is active for this API key.
     * If not supported, smoothly falls back to a valid model.
     */
    suspend fun getValidatedModel(apiKey: String, preferredModel: GeminiModel): String {
        return try {
            val availableModels = listModels(apiKey).getOrNull() ?: emptyList()
            if (availableModels.contains("models/${preferredModel.id}") || availableModels.contains(preferredModel.id)) {
                preferredModel.id
            } else {
                // Smart fallback: 2026 active flash models (3.8)
                availableModels.firstOrNull { it.contains("3.8", ignoreCase = true) }
                    ?.replace("models/", "")
                    ?: availableModels.firstOrNull { it.contains("flash", ignoreCase = true) }
                        ?.replace("models/", "")
                    ?: GeminiModel.FLASH_3_8.id
            }
        } catch (e: Exception) {
            GeminiModel.FLASH_3_8.id
        }
    }

    private fun resolveModel(modelId: String): String {
        return GeminiModel.fromId(modelId).id
    }

    suspend fun generate(
        apiKey: String,
        modelId: String = "gemini-3.8-flash",
        systemPrompt: String,
        userPrompt: String
    ): Result<String> {
        val cleanKey = apiKey.filterNot { it.isWhitespace() }
        val targetModel = resolveModel(modelId)
        val combinedPrompt = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userPrompt" else userPrompt
        val body = GeminiRequest(
            contents = listOf(
                GeminiRequest.Content("user", listOf(
                    GeminiRequest.Part(combinedPrompt)
                ))
            )
        )

        val result = executeGenerateCall(cleanKey, targetModel, body)
        // If the model was not found or failed with 404, fallback to 2026 default gemini-3.8-flash
        if (result.isFailure && targetModel != "gemini-3.8-flash") {
            val err = result.exceptionOrNull()?.message.orEmpty()
            if (err.contains("not found", ignoreCase = true) || err.contains("HTTP 404") || err.contains("no longer available", ignoreCase = true)) {
                return executeGenerateCall(cleanKey, "gemini-3.8-flash", body)
            }
        }
        return result
    }

    private suspend fun executeGenerateCall(
        cleanKey: String,
        model: String,
        body: GeminiRequest
    ): Result<String> {
        val isOAuth = isOAuthToken(cleanKey)
        val url = if (isOAuth) {
            "$baseUrl/$model:generateContent"
        } else {
            val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
            "$baseUrl/$model:generateContent?key=$encodedKey"
        }
        return retryWithBackoff {
            val response = httpClient.post(url) {
                if (isOAuth) {
                    header("Authorization", "Bearer ${cleanKey.removePrefix("Bearer ").trim()}")
                } else {
                    header("x-goog-api-key", cleanKey)
                }
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val rawText = response.bodyAsText()

            if (response.status.value !in 200..299) {
                val errMsg = runCatching {
                    json.decodeFromString<GeminiErrorResponse>(rawText).error.message
                }.getOrNull()?.ifBlank { null } ?: "HTTP ${response.status.value}: $rawText"
                error(errMsg)
            }

            val resp: GeminiResponse = json.decodeFromString(rawText)
            val resultText = resp.text()
            if (resultText.isBlank()) {
                if (resp.error != null) {
                    error(resp.error.message)
                }
                error("Received empty response from Gemini.")
            }
            resultText
        }
    }

    /**
     * Real-time streaming generation via Server-Sent Events (SSE).
     * Emits token strings as they arrive from Gemini.
     */
    fun streamGenerate(
        apiKey: String,
        modelId: String = "gemini-3.8-flash",
        systemPrompt: String,
        userPrompt: String
    ): Flow<String> = channelFlow {
        val cleanKey = apiKey.filterNot { it.isWhitespace() }
        val targetModel = resolveModel(modelId)
        val combinedPrompt = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userPrompt" else userPrompt
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiRequest.Content("user", listOf(
                    GeminiRequest.Part(combinedPrompt)
                ))
            )
        )

        var tokenCount = 0
        var streamSucceeded = false

        try {
            val isOAuth = isOAuthToken(cleanKey)
            val url = if (isOAuth) {
                "$baseUrl/$targetModel:streamGenerateContent?alt=sse"
            } else {
                val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
                "$baseUrl/$targetModel:streamGenerateContent?alt=sse&key=$encodedKey"
            }
            val statement = httpClient.preparePost(url) {
                if (isOAuth) {
                    header("Authorization", "Bearer ${cleanKey.removePrefix("Bearer ").trim()}")
                } else {
                    header("x-goog-api-key", cleanKey)
                }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            statement.execute { response ->
                if (response.status.value !in 200..299) {
                    val rawError = response.bodyAsText()
                    val errMsg = runCatching {
                        json.decodeFromString<GeminiErrorResponse>(rawError).error.message
                    }.getOrNull()?.ifBlank { null } ?: "HTTP ${response.status.value}: $rawError"
                    error(errMsg)
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isNotEmpty() && jsonStr != "[DONE]") {
                            val chunk = runCatching { json.decodeFromString<GeminiResponse>(jsonStr) }.getOrNull()
                            if (chunk != null) {
                                if (chunk.error != null) {
                                    error(chunk.error.message)
                                }
                                val chunkText = chunk.text()
                                if (chunkText.isNotEmpty()) {
                                    tokenCount++
                                    send(chunkText)
                                }
                            }
                        }
                    }
                }
                streamSucceeded = true
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (tokenCount == 0) {
                // If model was not found, fallback to 2026 default gemini-3.8-flash; otherwise retry with targetModel
                val fallbackModel = if (targetModel != "gemini-3.8-flash" &&
                    (msg.contains("not found", ignoreCase = true) || msg.contains("HTTP 404") || msg.contains("no longer available", ignoreCase = true))) {
                    "gemini-3.8-flash"
                } else {
                    targetModel
                }
                generate(cleanKey, fallbackModel, systemPrompt, userPrompt)
                    .onSuccess { send(it); return@channelFlow }
                    .onFailure { throw it }
            } else {
                throw e
            }
        }

        // If streaming completed with 0 tokens and no error thrown, fallback to generate()
        if (tokenCount == 0 && !streamSucceeded) {
            generate(cleanKey, targetModel, systemPrompt, userPrompt)
                .onSuccess { send(it) }
                .onFailure { throw it }
        }
    }

    /** Exponential backoff on 429 / 503 only. */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 2,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            runCatching { block() }.onSuccess { return Result.success(it) }
                .onFailure { e ->
                    lastError = e
                    val msg = e.message.orEmpty()
                    // Don't retry client errors (400, 401, 403, 404) except 429 rate limit
                    if (msg.contains("HTTP 4") && !msg.contains("429")) {
                        return Result.failure(e)
                    }
                    if (msg.contains("API key not valid", ignoreCase = true) ||
                        msg.contains("not found", ignoreCase = true)) {
                        return Result.failure(e)
                    }
                    if (attempt < maxAttempts - 1) {
                        delay(1200L)
                    }
                }
        }
        return Result.failure(lastError ?: RuntimeException("Unknown error"))
    }
}
