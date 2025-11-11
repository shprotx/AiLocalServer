package kz.shprot.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============ OpenRouter API Models ============

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 2000
)

@Serializable
data class OpenRouterResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenRouterChoice>? = null,
    val usage: OpenRouterUsage? = null,
    val error: OpenRouterError? = null
)

@Serializable
data class OpenRouterError(
    val message: String,
    val code: Int? = null
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenRouterUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

/**
 * Детальный ответ с метриками для одной модели
 */
@Serializable
data class OpenRouterDetailedResponse(
    val modelId: String,
    val content: String,
    val responseTimeMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val estimatedCost: Double?,
    val finishReason: String? = null
)

// ============ Web API Models для сравнения ============

/**
 * Запрос на сравнение моделей
 */
@Serializable
data class ModelComparisonRequest(
    val message: String,
    val models: List<String>, // ["meta-llama/llama-2-70b-chat", "mistralai/mistral-7b-instruct"]
    val sessionId: String = "default",
    val temperature: Double = 0.7
)

/**
 * Результат сравнения одной модели
 */
@Serializable
data class ModelComparisonResult(
    val modelId: String,
    val modelName: String, // Красивое имя для UI
    val response: String,
    val responseTimeMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val estimatedCost: Double?,
    val estimatedCostFormatted: String, // "$0.0023" или "бесплатно"
    val status: String = "success" // "success" или "error"
)

/**
 * Общий ответ со сравнением всех моделей
 */
@Serializable
data class ModelComparisonResponse(
    val results: List<ModelComparisonResult>,
    val totalTimeMs: Long
)

/**
 * Предустановленные модели для быстрого выбора
 */
object PresetModels {
    // Начало списка - совсем слабые/тупые модели (1-3B параметров)
    val LIGHT = listOf(
        "meta-llama/llama-3.2-1b-instruct" to "Llama 3.2 1B (крошечная)",
        "meta-llama/llama-3.2-3b-instruct" to "Llama 3.2 3B (очень маленькая)"
    )

    // Середина - средние модели
    val MEDIUM = listOf(
        "meta-llama/llama-3.1-70b-instruct" to "Llama 3.1 70B Instruct",
        "mistralai/mixtral-8x7b-instruct" to "Mixtral 8x7B Instruct"
    )

    // Конец списка - тяжелые/продвинутые модели
    val HEAVY = listOf(
        "meta-llama/llama-3-70b-instruct" to "Llama 3 70B Instruct",
        "qwen/qwen-2.5-72b-instruct" to "Qwen 2.5 72B Instruct"
    )

    val ALL = LIGHT + MEDIUM + HEAVY

    fun getDisplayName(modelId: String): String =
        ALL.firstOrNull { it.first == modelId }?.second ?: modelId
}
