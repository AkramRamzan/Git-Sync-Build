package com.aro.code.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BridgeChatRequest(
    val model: String,
    val prompt: String,
    @SerialName("system_prompt") val systemPrompt: String = ""
)

/**
 * Client for communicating with the local Termux / VPS Anti-Gravity Bridge Server.
 * Supports real-time streaming of tokens for Claude Sonnet 4.6, Claude Opus 4.6,
 * GPT-OSS 120B, and Gemini 4 Argon.
 */
@Singleton
class AntiGravityBridgeClient @Inject constructor(
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Checks if the local Termux / VPS bridge server is currently running.
     */
    suspend fun isBridgeAlive(bridgeUrl: String): Boolean {
        return try {
            val cleanUrl = bridgeUrl.trimEnd('/')
            val response = httpClient.get("$cleanUrl/v1/health") {
                timeout {
                    requestTimeoutMillis = 2500
                    connectTimeoutMillis = 2000
                }
            }
            response.status.value in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Real-time SSE streaming from the local Termux / VPS bridge.
     */
    fun streamGenerate(
        bridgeUrl: String,
        bridgeToken: String?,
        modelId: String,
        systemPrompt: String,
        userPrompt: String
    ): Flow<String> = channelFlow {
        val cleanUrl = bridgeUrl.trimEnd('/')
        val requestBody = BridgeChatRequest(
            model = modelId,
            prompt = userPrompt,
            systemPrompt = systemPrompt
        )

        val statement = httpClient.preparePost("$cleanUrl/v1/chat/stream") {
            contentType(ContentType.Application.Json)
            if (!bridgeToken.isNullOrBlank()) {
                header("Authorization", "Bearer $bridgeToken")
            }
            setBody(requestBody)
        }

        statement.execute { response ->
            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                error("Termux Bridge Error (HTTP ${response.status.value}): $errorBody")
            }

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) {
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        break
                    }
                    if (payload.isNotEmpty()) {
                        // Extract text if payload is JSON {"token": "..."} or emit raw text
                        val token = runCatching {
                            json.parseToJsonElement(payload).jsonObject["token"]?.jsonPrimitive?.content
                        }.getOrNull() ?: payload
                        send(token)
                    }
                }
            }
        }
    }
}
