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
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("project_path") val projectPath: String? = null
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
            val response = httpClient.get("$cleanUrl/v1/health")
            response.status.value in 200..299
        } catch (e: Exception) {
            try {
                // Secondary check against root /
                val cleanUrl = bridgeUrl.trimEnd('/')
                val fallbackResp = httpClient.get(cleanUrl)
                fallbackResp.status.value in 200..299
            } catch (e2: Exception) {
                false
            }
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
        userPrompt: String,
        projectPath: String? = null
    ): Flow<String> = channelFlow {
        val cleanUrl = bridgeUrl.trimEnd('/')
        val requestBody = BridgeChatRequest(
            model = modelId,
            prompt = userPrompt,
            systemPrompt = systemPrompt,
            projectPath = projectPath
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
                if (line.startsWith("data:")) {
                    val rawPayload = line.removePrefix("data:")
                    val trimmed = rawPayload.trim()
                    if (trimmed == "[DONE]") {
                        break
                    }
                    if (trimmed.isNotEmpty()) {
                        // Extract text if payload is JSON {"token": "..."} or emit raw payload
                        val token = runCatching {
                            json.parseToJsonElement(trimmed).jsonObject["token"]?.jsonPrimitive?.content
                        }.getOrNull() ?: rawPayload.removePrefix(" ")
                        send(token)
                    }
                }
            }
        }
    }
}
