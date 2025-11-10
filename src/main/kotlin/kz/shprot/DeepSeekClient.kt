package kz.shprot

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kz.shprot.models.*

/**
 * Клиент для DeepSeek API (OpenAI-совместимый)
 *
 * API Endpoint: https://api.deepseek.com/v1/chat/completions
 * Модели: deepseek-chat, deepseek-reasoner
 */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String = "deepseek-chat"
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    private val apiEndpoint = "https://api.deepseek.com/v1/chat/completions"

    /**
     * Отправка сообщения в DeepSeek API
     * @param messages список сообщений в старом формате (Message)
     * @param temperature температура генерации (0.0 - 2.0)
     * @param useJsonFormat использовать JSON формат ответа
     */
    suspend fun sendMessage(
        messages: List<Message>,
        temperature: Double = 0.7,
        useJsonFormat: Boolean = true
    ): String {
        // Конвертируем из старого формата в OpenAI формат
        val openAIMessages = messages.map { it.toOpenAI() }

        val request = OpenAIChatRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            maxTokens = 4000,
            responseFormat = if (useJsonFormat) ResponseFormat("json_object") else null,
            stream = false
        )

        println("=" .repeat(80))
        println("🚀 DeepSeek API Request:")
        println("  Endpoint: $apiEndpoint")
        println("  Model: $model")
        println("  Temperature: $temperature")
        println("  Messages count: ${messages.size}")
        println("  JSON format: $useJsonFormat")

        // Логируем полный request body
        val requestJson = Json { prettyPrint = true }.encodeToString(OpenAIChatRequest.serializer(), request)
        println("📤 Request Body:")
        println(requestJson)
        println("-" .repeat(80))

        return runCatching {
            val httpResponse: HttpResponse = client.post(apiEndpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            // Получаем raw response как текст для логирования
            val rawResponseBody = httpResponse.bodyAsText()

            println("📥 Response Status: ${httpResponse.status}")
            println("📥 Raw Response Body:")
            println(rawResponseBody)
            println("-" .repeat(80))

            // Проверяем статус код
            if (!httpResponse.status.isSuccess()) {
                // Пытаемся распарсить как error response
                val errorResponse = runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString<ErrorResponse>(rawResponseBody)
                }.getOrNull()

                if (errorResponse != null) {
                    println("❌ API Error: ${errorResponse.error.message}")
                    println("❌ Error Type: ${errorResponse.error.type}")
                    println("❌ Error Code: ${errorResponse.error.code}")
                    println("=" .repeat(80))
                    return "API Error: ${errorResponse.error.message}"
                }
            }

            // Пытаемся распарсить ответ
            val response: OpenAIChatResponse = Json { ignoreUnknownKeys = true }
                .decodeFromString(rawResponseBody)

            val rawText = response.choices.firstOrNull()?.message?.content
                ?: "Ошибка: пустой ответ от модели"

            // Логируем usage
            response.usage?.let { usage ->
                println("📊 Tokens: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, total=${usage.totalTokens}")
            }

            println("✅ Extracted Content: $rawText")
            println("=" .repeat(80))

            rawText
        }.getOrElse { e ->
            println("❌ API ERROR: ${e.message}")
            println("❌ Error Type: ${e::class.simpleName}")
            e.printStackTrace()
            println("=" .repeat(80))
            "Ошибка при обращении к API: ${e.message}"
        }
    }

    /**
     * Отправка сообщения с парсингом структурированного ответа
     */
    suspend fun sendMessageWithHistory(
        messages: List<Message>,
        temperature: Double = 0.7
    ): LLMStructuredResponse {
        val rawResponse = sendMessage(messages, temperature, useJsonFormat = true)

        return runCatching {
            println("📝 Parsing JSON response: $rawResponse")
            Json.decodeFromString<LLMStructuredResponse>(rawResponse)
        }.getOrElse { e ->
            println("⚠️ Failed to parse JSON: ${e.message}")
            // Fallback: если не удалось распарсить, возвращаем как есть
            LLMStructuredResponse(
                title = "Ответ",
                message = rawResponse
            )
        }
    }

    fun close() {
        client.close()
    }
}
