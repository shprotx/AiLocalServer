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
    val title: String? = null,
    val isMultiAgent: Boolean = false,
    val agents: List<AgentResponseData>? = null
)

@Serializable
data class AgentResponseData(
    val role: String,
    val content: String
)

@Serializable
data class LLMStructuredResponse(
    val title: String,
    val message: String
)
