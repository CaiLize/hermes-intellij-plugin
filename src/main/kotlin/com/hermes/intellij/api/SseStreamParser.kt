package com.hermes.intellij.api

import com.hermes.intellij.api.models.StreamDelta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Parses Server-Sent Events (SSE) text/event-stream lines into StreamDelta objects.
 */
object SseStreamParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transforms a flow of raw SSE lines into a flow of StreamDelta objects.
     * Handles: data lines, [DONE] signal, comment lines, blank lines.
     */
    fun parse(lines: Flow<String>): Flow<StreamDelta> = flow {
        val dataBuffer = StringBuilder()

        lines.collect { line ->
            when {
                line.startsWith("data: [DONE]") || line.startsWith("data:[DONE]") -> {
                    return@collect
                }

                line.startsWith("data: ") -> {
                    val payload = line.removePrefix("data: ").trim()
                    if (dataBuffer.isNotEmpty()) {
                        dataBuffer.append(payload)
                    } else {
                        dataBuffer.append(payload)
                    }
                }

                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    dataBuffer.append(payload)
                }

                line.isBlank() -> {
                    // Blank line signals end of an event
                    if (dataBuffer.isNotEmpty()) {
                        val data = dataBuffer.toString()
                        dataBuffer.clear()
                        try {
                            val delta = json.decodeFromString<StreamDelta>(data)
                            emit(delta)
                        } catch (_: Exception) {
                            // Skip malformed data, continue streaming
                        }
                    }
                }

                line.startsWith(":") -> {
                    // SSE comment, ignore
                }

                // Ignore event:, id:, retry: lines
                else -> {}
            }
        }

        // Flush any remaining buffered data
        if (dataBuffer.isNotEmpty()) {
            val data = dataBuffer.toString()
            try {
                val delta = json.decodeFromString<StreamDelta>(data)
                emit(delta)
            } catch (_: Exception) {
                // Skip malformed trailing data
            }
        }
    }
}
