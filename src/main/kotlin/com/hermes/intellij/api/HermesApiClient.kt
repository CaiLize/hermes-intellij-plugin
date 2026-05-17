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

    // FIX: 超时配置 - 与 Hermes Agent 端保持一致
    // HERMES_STREAM_READ_TIMEOUT=300, HERMES_API_TIMEOUT=600
    // 客户端请求超时设置为 600 秒，确保不会先于服务器超时
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .version(HttpClient.Version.HTTP_1_1)  // HTTP/1.1 better for SSE
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
     * 
     * FIX: 超时配置 - 使用 600 秒超时，与 HERMES_API_TIMEOUT 保持一致
     */
    fun streamChatWithSession(request: ChatRequest, sessionId: String? = null): StreamChatWithSession {
        val streamRequest = request.copy(stream = true)
        val body = json.encodeToString(streamRequest)
        val endpoint = resolveBase() + "/chat/completions"

        // FIX: 使用 600 秒超时，与 Hermes Agent 端的 HERMES_API_TIMEOUT=600 保持一致
        // 这样客户端不会先于服务器超时，避免"输出一半就没了"的问题
        val httpRequest = buildHttpRequest(endpoint, body, sessionId, timeoutSeconds = 600)

        // Send request synchronously to get response headers
        // FIX: Wrap in try-catch to handle connection-level errors
        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        } catch (e: Exception) {
            logger.warn("[ApiClient] HTTP request failed: ${e.javaClass.simpleName}: ${e.message}")
            throw HermesApiException(0, "Connection failed: ${e.message}")
        }

        // Extract session ID from response header
        val extractedSessionId = response.headers().firstValue("X-Hermes-Session-Id").orElse(null)
        logger.info("[ApiClient] Received session ID from response header: $extractedSessionId")

        // Check for errors immediately after connection
        if (response.statusCode() != 200) {
            val statusCode = response.statusCode()
            // FIX: SEC-004 - 日志脱敏，不记录完整错误响应体
            val errorBodyPreview = try {
                val fullBody = response.body().toList().joinToString("\n")
                if (fullBody.length > 200) "${fullBody.take(200)}..." else fullBody
            } catch (e: Exception) {
                "(unable to read error body)"
            }
            // 仅在 DEBUG 级别记录完整响应体，生产环境只记录状态码
            if (logger.isDebugEnabled) {
                logger.debug("[ApiClient] Error response ($statusCode): $errorBodyPreview")
            } else {
                logger.warn("[ApiClient] API returned error: $statusCode")
            }
            throw HermesApiException(statusCode, "HTTP $statusCode")
        }

        // FIX: Wrap the flow collection with proper error handling
        // The flow will emit deltas as they arrive, and will complete when the stream ends
        val deltas = SseStreamParser.parse(
            flow {
                val iterator = response.body().iterator()
                try {
                    while (iterator.hasNext()) {
                        val line = iterator.next()
                        emit(line)
                    }
                } catch (e: java.io.IOException) {
                    // FIX: Log and rethrow - this indicates the stream was interrupted
                    logger.warn("[ApiClient] SSE stream interrupted: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                } catch (e: Exception) {
                    // FIX: Log unexpected errors but continue - don't silently swallow
                    logger.warn("[ApiClient] SSE stream error: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
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

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            logger.warn("[ApiClient] HTTP request failed: ${e.javaClass.simpleName}: ${e.message}")
            throw HermesApiException(0, "Connection failed: ${e.message}")
        }

        if (response.statusCode() != 200) {
            val statusCode = response.statusCode()
            // FIX: SEC-004 - 日志脱敏
            val errorBodyPreview = try {
                val bodyContent = response.body()
                if (bodyContent.length > 200) "${bodyContent.take(200)}..." else bodyContent
            } catch (e: Exception) {
                "(unable to read error body)"
            }
            if (logger.isDebugEnabled) {
                logger.debug("[ApiClient] Non-streaming error ($statusCode): $errorBodyPreview")
            }
            throw HermesApiException(statusCode, "HTTP $statusCode")
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
            logger.info("[ApiClient] DELETE /sessions/$sessionId -> ${response.statusCode()}")
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.warn("[ApiClient] DELETE /sessions/$sessionId failed: ${e.message}", e)
            false
        }
    }

    private fun buildHttpRequest(
        endpoint: String,
        body: String,
        sessionId: String? = null,
        timeoutSeconds: Long = 600  // FIX: Default to 600s to match HERMES_API_TIMEOUT
    ): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")  // FIX: Prevent proxy caching of SSE streams
            // FIX: Remove "Connection" header — Java HttpClient manages this automatically.
            // Setting it manually throws "restricted header name" error.
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
