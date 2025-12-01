package kz.shprot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val message: String,
    val chatId: Int,  // Теперь используем chatId вместо sessionId
    val temperature: Double? = 0.6,
    val compressContext: Boolean = false,
    val compressSystemPrompt: Boolean = false,
    val useRAG: Boolean = true,  // Использовать RAG для обогащения контекста
    // Новые параметры для гибридной фильтрации
    val ragFilterMode: String = "default",  // "default", "strict", "lenient"
    val useReranking: Boolean = true        // Включить переранжирование
)

@Serializable
data class ChatResponse(
    val response: String,
    val title: String? = null,
    val isMultiAgent: Boolean = false,
    val agents: List<AgentResponseData>? = null,
    val tokenUsage: TokenUsageInfo? = null,
    val contextWindowUsage: ContextWindowUsage? = null,
    val usedTools: List<String>? = null, // Список использованных MCP инструментов
    val ragUsed: Boolean = false, // Был ли использован RAG
    val ragContext: String? = null, // Контекст из базы знаний (если был использован)
    // Новые поля для детальной статистики RAG
    val ragChunksCount: Int? = null,           // Количество использованных чанков
    val ragFilteringStats: RAGFilteringStatsData? = null,  // Статистика фильтрации
    val ragRerankingStats: RAGRerankingStatsData? = null,   // Статистика reranking
    val ragSources: List<SourceInfoData>? = null  // Список источников (документов)
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

// ==================== RAG / Knowledge Base Models ====================

@Serializable
data class UploadFileResponse(
    val success: Boolean,
    val documentId: Int,
    val filename: String,
    val message: String
)

@Serializable
data class DocumentInfo(
    val id: Int,
    val filename: String,
    val fileType: String,
    val uploadDate: Long,
    val totalChunks: Int
)

@Serializable
data class KnowledgeBaseStatsResponse(
    val totalDocuments: Int,
    val totalChunks: Int,
    val documents: List<DocumentInfo>
)

@Serializable
data class DeleteDocumentResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class SimpleErrorResponse(
    val error: String
)

// ==================== RAG Comparison Models ====================

@Serializable
data class CompareRequest(
    val message: String,
    val chatId: Int,
    val temperature: Double? = 0.6,
    val compressContext: Boolean = false,
    val compressSystemPrompt: Boolean = false,
    // Новые параметры для гибридной фильтрации
    val ragFilterMode: String = "default",
    val useReranking: Boolean = true
)

@Serializable
data class CompareResponse(
    val withRAG: ChatResponse,
    val withoutRAG: ChatResponse,
    val ragContext: String?,
    val ragChunksCount: Int, // Количество найденных чанков
    val similarityScores: List<Double>? = null, // Scores релевантности чанков
    // Новые поля для детальной статистики
    val filteringStats: RAGFilteringStatsData? = null,
    val rerankingStats: RAGRerankingStatsData? = null
)

// ==================== RAG Statistics Models ====================

/**
 * Статистика фильтрации для передачи в клиент
 */
@Serializable
data class RAGFilteringStatsData(
    val totalChunks: Int,
    val afterPrimaryFilter: Int,
    val afterSmartFilter: Int,
    val finalResults: Int,
    val avgSimilarityBefore: Double,
    val avgSimilarityAfter: Double,
    val minSimilarity: Double,
    val maxSimilarity: Double,
    val processingTimeMs: Long
)

/**
 * Статистика reranking для передачи в клиент
 */
@Serializable
data class RAGRerankingStatsData(
    val totalCandidates: Int,
    val rerankedCount: Int,
    val avgScoreBefore: Double,
    val avgScoreAfter: Double,
    val scoreImprovement: Double,
    val processingTimeMs: Long
)

/**
 * Информация об источнике (документе) использованном в RAG
 */
@Serializable
data class SourceInfoData(
    val documentId: Int,
    val filename: String,
    val fileType: String
)

// ==================== TOOL REGISTRY API Models ====================

/**
 * Запрос на выполнение инструмента
 */
@Serializable
data class ToolExecuteRequest(
    val toolName: String,
    val arguments: String  // JSON строка с аргументами
)

/**
 * Запрос на регистрацию проекта
 */
@Serializable
data class RegisterProjectRequest(
    val path: String,
    val name: String? = null
)

/**
 * Запрос на выполнение команды
 */
@Serializable
data class CommandRequest(
    val command: String
)
