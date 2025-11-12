package kz.shprot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val message: String,
    val sessionId: String = "default",
    val temperature: Double? = 0.6
)

@Serializable
data class ChatResponse(
    val response: String,
    val title: String? = null,
    val isMultiAgent: Boolean = false,
    val agents: List<AgentResponseData>? = null,
    val tokenUsage: TokenUsageInfo? = null
)

@Serializable
data class AgentResponseData(
    val role: String,
    val content: String
)

@Serializable
data class TokenUsageInfo(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val estimatedCostRub: Double,
    val modelName: String
)

@Serializable
data class SessionTokenStats(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalTokens: Int,
    val totalCostRub: Double,
    val messageCount: Int
)

@Serializable
data class LLMStructuredResponse(
    val title: String,
    val message: String
)
