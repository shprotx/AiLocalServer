package kz.shprot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val message: String,
    val sessionId: String = "default"
)

@Serializable
data class ChatResponse(
    val response: String,
    val title: String? = null
)

@Serializable
data class LLMStructuredResponse(
    val title: String,
    val message: String
)
