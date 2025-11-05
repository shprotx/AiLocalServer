package kz.shprot.models

import kotlinx.serialization.Serializable

@Serializable
data class YandexCompletionRequest(
    val modelUri: String,
    val completionOptions: CompletionOptions,
    val messages: List<Message>,
    val json_schema: JsonSchemaParam? = null
)

@Serializable
data class CompletionOptions(
    val stream: Boolean = false,
    val temperature: Double = 0.6,
    val maxTokens: String = "2000"
)

@Serializable
data class Message(
    val role: String,
    val text: String
)

@Serializable
data class YandexCompletionResponse(
    val result: CompletionResult
)

@Serializable
data class CompletionResult(
    val alternatives: List<Alternative>,
    val usage: Usage,
    val modelVersion: String
)

@Serializable
data class Alternative(
    val message: Message,
    val status: String
)

@Serializable
data class Usage(
    val inputTextTokens: String,
    val completionTokens: String,
    val totalTokens: String
)

@Serializable
data class JsonSchemaParam(
    val schema: JsonSchema
)

@Serializable
data class JsonSchema(
    val type: String = "object",
    val properties: Map<String, JsonSchemaProperty>,
    val required: List<String>
)

@Serializable
data class JsonSchemaProperty(
    val type: String,
    val description: String? = null,
    val title: String? = null
)
