package com.hermes.intellij.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamDelta(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null
)
