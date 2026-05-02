package com.hermes.intellij.api

import com.hermes.intellij.api.models.ChatRequest
import com.hermes.intellij.api.models.ChatResponse
import com.hermes.intellij.api.models.StreamDelta
import com.hermes.intellij.services.HermesSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pair of session ID extracted from response header and the stream of deltas.
 * Session ID is only available in the first element of the flow.
 */
data class StreamChatWithSession(
    val sessionId: String?,
    val deltas: Flow<StreamDelta>
)

@Service(Service.Level.APP)
class HermesApiClient {

    private val logger = Logger.getInstance(HermesApiClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val settings: HermesSettingsState
        get() = ApplicationManager.getApplication().getService(HermesSettingsState::class.java)

    /**
     * Resolve the base API path. If the endpoint already ends with /v1, use it directly.
     * Otherwise append /v1.
     */
    private fun resolveBase(): String {
        val base = settings.apiEndpoint.trimEnd('/')
        return if (base.endsWith("/v1")) base else "$base/v1"
    }

    /**
     * Stream chat completions from the Hermes API and extract session ID from response header.
     * Returns a pair of (sessionId, flow of deltas).
     * Session ID is extracted from "X-Hermes-Session-Id" response header.
     */
    fun streamChatWithSession(request: ChatRequest, sessionId: String? = null): StreamChatWithSession {
        val streamRequest = request.copy(stream = true)
        val body = json.encodeToString(streamRequest)
        val endpoint = resolveBase() + "/chat/completions"

        // Use a longer timeout for SSE streaming — responses can take minutes
        val httpRequest = buildHttpRequest(endpoint, body, sessionId, timeoutSeconds = 600)

        // Send request synchronously to get response headers
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

        // Extract session ID from response header
        val extractedSessionId = response.headers().firstValue("X-Hermes-Session-Id").orElse(null)
        logger.info("[ApiClient] Received session ID from response header: $extractedSessionId")

        // Check for errors
        if (response.statusCode() != 200) {
            val statusCode = response.statusCode()
            val errorBody = response.body().toList().joinToString("\n")
            throw HermesApiException(statusCode, errorBody)
        }

        val deltas = SseStreamParser.parse(
            flow {
                val iterator = response.body().iterator()
                while (iterator.hasNext()) {
                    emit(iterator.next())
                }
            }
        ).flowOn(Dispatchers.IO)

        return StreamChatWithSession(sessionId = extractedSessionId, deltas = deltas)
    }

    /**
     * Send a non-streaming chat completion request.
     * Also extracts session ID from response header.
     */
    data class ChatWithSessionResult(
        val sessionId: String?,
        val response: ChatResponse
    )

    fun chat(request: ChatRequest, sessionId: String? = null): ChatWithSessionResult {
        val nonStreamRequest = request.copy(stream = false)
        val body = json.encodeToString(nonStreamRequest)
        val endpoint = resolveBase() + "/chat/completions"

        val httpRequest = buildHttpRequest(endpoint, body, sessionId)

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw HermesApiException(response.statusCode(), response.body())
        }

        val extractedSessionId = response.headers().firstValue("X-Hermes-Session-Id").orElse(null)
        logger.info("[ApiClient] Received session ID from non-streaming response: $extractedSessionId")

        val chatResponse = json.decodeFromString<ChatResponse>(response.body())
        return ChatWithSessionResult(sessionId = extractedSessionId, response = chatResponse)
    }

    /**
     * Test the connection to the Hermes API.
     * Returns true if the endpoint is reachable.
     */
    fun testConnection(): Boolean {
        return try {
            val endpoint = resolveBase() + "/models"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .apply {
                    val apiKey = settings.apiKey
                    if (apiKey.isNotBlank()) {
                        header("Authorization", "Bearer $apiKey")
                    }
                }
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.info("Hermes connection test failed", e)
            false
        }
    }

    /**
     * Delete a session from the Hermes server.
     * DELETE /v1/sessions/{session_id}
     * Returns true if deletion was successful.
     */
    fun deleteSession(sessionId: String): Boolean {
        return try {
            val endpoint = resolveBase() + "/sessions/$sessionId"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .apply {
                    val apiKey = settings.apiKey
                    if (apiKey.isNotBlank()) {
                        header("Authorization", "Bearer $apiKey")
                    }
                }
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            // 仅记录状态码，不记录响应体（可能包含敏感信息）
            logger.info("Hermes delete session response: ${response.statusCode()}")
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.warn("Hermes delete session failed for $sessionId", e)
            false
        }
    }

    private fun buildHttpRequest(
        endpoint: String,
        body: String,
        sessionId: String? = null,
        timeoutSeconds: Long = 120
    ): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))

        val apiKey = settings.apiKey
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        // Add session ID header if provided (for continuing a conversation)
        if (sessionId != null) {
            builder.header("X-Hermes-Session-Id", sessionId)
        }

        return builder.build()
    }

    companion object {
        fun getInstance(): HermesApiClient =
            ApplicationManager.getApplication().getService(HermesApiClient::class.java)
    }
}

class HermesApiException(val statusCode: Int, val errorBody: String) :
    RuntimeException("Hermes API error ($statusCode): $errorBody")
