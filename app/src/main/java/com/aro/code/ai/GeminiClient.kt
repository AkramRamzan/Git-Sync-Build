package com.aro.code.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
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
    @Serializable data class Candidate(val content: GeminiRequest.Content)
    fun text(): String = candidates.firstOrNull()
        ?.content?.parts?.firstOrNull()?.text ?: ""
}

/**
 * Module 5 – Gemini API Client with exponential backoff.
 * Authenticates using user-supplied API key stored in CredentialStore.
 */
@Singleton
class GeminiClient @Inject constructor(
    private val httpClient: HttpClient
) {
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

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
