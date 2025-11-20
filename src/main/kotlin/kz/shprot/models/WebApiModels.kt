package kz.shprot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val message: String,
    val chatId: Int,  // Теперь используем chatId вместо sessionId
    val temperature: Double? = 0.6,
    val compressContext: Boolean = false,
    val compressSystemPrompt: Boolean = false
)

@Serializable
data class ChatResponse(
    val response: String,
    val title: String? = null,
    val isMultiAgent: Boolean = false,
    val agents: List<AgentResponseData>? = null,
    val tokenUsage: TokenUsageInfo? = null,
    val contextWindowUsage: ContextWindowUsage? = null,
    val usedTools: List<String>? = null // Список использованных MCP инструментов
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
data class ContextWindowUsage(
    val currentTokens: Int,
    val maxTokens: Int,
    val usagePercent: Double,
    val isCompressed: Boolean
)

@Serializable
data class LLMStructuredResponse(
    val title: String,
    val message: String,
    @kotlinx.serialization.SerialName("tool_calls")
    private val toolCallsPlural: kotlinx.serialization.json.JsonElement? = null, // Новый формат (множественное число)
    @kotlinx.serialization.SerialName("tool_call")
    private val toolCallSingular: kotlinx.serialization.json.JsonElement? = null // Старый формат (единственное число, может быть массивом или пустым объектом)
) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

    // Публичное свойство которое проверяет оба варианта и парсит только массивы
    val tool_calls: List<ToolCall>?
        get() {
            // Проверяем toolCallsPlural (новый формат)
            toolCallsPlural?.let { element ->
                if (element is kotlinx.serialization.json.JsonArray) {
                    return try {
                        json.decodeFromJsonElement(kotlinx.serialization.serializer<List<ToolCall>>(), element)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            // Проверяем toolCallSingular (старый формат)
            toolCallSingular?.let { element ->
                if (element is kotlinx.serialization.json.JsonArray) {
                    return try {
                        json.decodeFromJsonElement(kotlinx.serialization.serializer<List<ToolCall>>(), element)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            return null
        }
}

@Serializable
data class ToolCall(
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement>
)

// Модели для работы с чатами

@Serializable
data class Chat(
    val id: Int,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ChatListResponse(
    val chats: List<Chat>
)

@Serializable
data class CreateChatRequest(
    val title: String = "Новый чат"
)

@Serializable
data class CreateChatResponse(
    val chatId: Int,
    val title: String
)

@Serializable
data class ChatMessage(
    val id: Int,
    val chatId: Int,
    val role: String,
    val content: String,
    val timestamp: Long
)

@Serializable
data class MessagesResponse(
    val messages: List<ChatMessage>
)

// Модели для MCP Orchestrator

@Serializable
data class OrchestratorRequest(
    val task: String,
    val temperature: Double? = 0.6
)

@Serializable
data class OrchestratorResponse(
    val success: Boolean,
    val finalAnswer: String,
    val toolCalls: List<ToolCallInfo>,
    val iterations: Int
)

@Serializable
data class ToolCallInfo(
    val iteration: Int,
    val toolName: String,
    val parameters: String,
    val result: String
)
