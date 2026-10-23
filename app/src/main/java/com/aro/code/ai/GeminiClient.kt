package com.aro.code.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
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
    @Serializable data class Content(val role: String, val parts: List<Part>)
    @Serializable data class Part(val text: String)
    @Serializable data class GenerationConfig(
        val temperature: Float = 0.2f,
        @SerialName("maxOutputTokens") val maxOutputTokens: Int = 8192
    )
}

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate> = emptyList()
) {
    @Serializable data class Candidate(val content: GeminiRequest.Content? = null)
    fun text(): String = candidates.firstOrNull()
        ?.content?.parts?.firstOrNull()?.text ?: ""
}

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
        val body = GeminiRequest(
            contents = listOf(
                GeminiRequest.Content("user", listOf(
                    GeminiRequest.Part(systemPrompt + "\n\n" + userPrompt)
                ))
            )
        )
        return retryWithBackoff {
            val resp: GeminiResponse = httpClient.post(
                "$baseUrl/$modelId:generateContent?key=$apiKey"
            ) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
            resp.text()
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
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiRequest.Content("user", listOf(
                    GeminiRequest.Part(systemPrompt + "\n\n" + userPrompt)
                ))
            )
        )

        try {
            val statement = httpClient.preparePost(
                "$baseUrl/$modelId:streamGenerateContent?alt=sse&key=$apiKey"
            ) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            statement.execute { response ->
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
                                    send(chunkText)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to one-shot generation if SSE streaming encounters transport errors
            generate(apiKey, modelId, systemPrompt, userPrompt)
                .onSuccess { send(it) }
                .onFailure { throw it }
        }
    }

    /** Exponential backoff with jitter on 429/503. */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 5,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            runCatching { block() }.onSuccess { return Result.success(it) }
                .onFailure { e ->
                    lastError = e
                    val base = (2.0.pow(attempt) * 1000).toLong()
                    val jitter = Random.nextLong(0, 500)
                    delay(min(base + jitter, 32_000L))
                }
        }
        return Result.failure(lastError ?: RuntimeException("Unknown error"))
    }
}
