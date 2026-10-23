package com.aro.code.ai

import io.ktor.client.HttpClient
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

/**
 * Module 5 & Anti-Gravity – Gemini API Client.
 * Supports standard generation and Server-Sent Events (SSE) token-by-token streaming.
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

    suspend fun generate(
        apiKey: String,
        modelId: String = "gemini-2.0-flash",
        systemPrompt: String,
        userPrompt: String
    ): Result<String> {
        val trimmedKey = apiKey.trim()
        val combinedPrompt = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userPrompt" else userPrompt
        val body = GeminiRequest(
            contents = listOf(
                GeminiRequest.Content("user", listOf(
                    GeminiRequest.Part(combinedPrompt)
                ))
            )
        )

        return retryWithBackoff {
            val response = httpClient.post(
                "$baseUrl/$modelId:generateContent?key=$trimmedKey"
            ) {
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
        modelId: String = "gemini-2.0-flash",
        systemPrompt: String,
        userPrompt: String
    ): Flow<String> = channelFlow {
        val trimmedKey = apiKey.trim()
        val combinedPrompt = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userPrompt" else userPrompt
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiRequest.Content("user", listOf(
                    GeminiRequest.Part(combinedPrompt)
                ))
            )
        )

        var tokenCount = 0

        try {
            val statement = httpClient.preparePost(
                "$baseUrl/$modelId:streamGenerateContent?alt=sse&key=$trimmedKey"
            ) {
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
                            runCatching {
                                val chunk = json.decodeFromString<GeminiResponse>(jsonStr)
                                val chunkText = chunk.text()
                                if (chunkText.isNotEmpty()) {
                                    tokenCount++
                                    send(chunkText)
                                }
                            }
                        }
                    }
                }
            }

            // If streaming produced no tokens at all, fallback to generate()
            if (tokenCount == 0) {
                generate(trimmedKey, modelId, systemPrompt, userPrompt)
                    .onSuccess { send(it) }
                    .onFailure { throw it }
            }
        } catch (e: Exception) {
            // If already sent some tokens, rethrow; otherwise attempt one-shot fallback
            if (tokenCount == 0) {
                generate(trimmedKey, modelId, systemPrompt, userPrompt)
                    .onSuccess { send(it) }
                    .onFailure { throw it }
            } else {
                throw e
            }
        }
    }

    /** Exponential backoff with jitter on 429/503. */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            runCatching { block() }.onSuccess { return Result.success(it) }
                .onFailure { e ->
                    lastError = e
                    val base = (2.0.pow(attempt) * 1000).toLong()
                    val jitter = Random.nextLong(0, 500)
                    delay(min(base + jitter, 16_000L))
                }
        }
        return Result.failure(lastError ?: RuntimeException("Unknown error"))
    }
}
