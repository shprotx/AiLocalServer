package kz.shprot.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DeepSeek API использует OpenAI-совместимый формат
 */

@Serializable
data class OpenAIChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String
)

@Serializable
data class ResponseFormat(
    val type: String  // "text" или "json_object"
)

@Serializable
data class OpenAIChatRequest(
    val model: String,  // "deepseek-chat", "deepseek-reasoner"
    val messages: List<OpenAIChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 4000,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    val stream: Boolean = false
)

@Serializable
data class OpenAIChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: OpenAIChatMessage,
    @SerialName("finish_reason")
    val finishReason: String
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

/**
 * Error response от API
 */
@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String? = null,
    val code: String? = null
)

/**
 * Старый формат Message для обратной совместимости
 * Используется в ChatHistory и AgentManager
 */
@Serializable
data class Message(
    val role: String,
    val text: String
) {
    // Конвертер в OpenAI формат
    fun toOpenAI() = OpenAIChatMessage(role = role, content = text)
}
