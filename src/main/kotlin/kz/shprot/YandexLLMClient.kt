package kz.shprot

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kz.shprot.models.*

/**
 * Результат API вызова с текстом и информацией о токенах
 */
data class MessageWithUsage(
    val text: String,
    val usage: Usage?
)

/**
 * Структурированный ответ с информацией о токенах
 */
data class StructuredResponseWithUsage(
    val response: LLMStructuredResponse,
    val usage: Usage?
)

class YandexLLMClient(
    private val apiKey: String,
    private val modelUri: String,
) {
    // Общий JSON парсер с правильными настройками
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
    }

    /**
     * Отправляет сообщение и возвращает только текст (для обратной совместимости)
     */
    suspend fun sendMessage(messages: List<Message>, temperature: Double = 0.6): String {
        return sendMessageWithUsage(messages, temperature).text
    }

    /**
     * Отправляет сообщение и возвращает текст + информацию о токенах
     */
    suspend fun sendMessageWithUsage(messages: List<Message>, temperature: Double = 0.6): MessageWithUsage {
        val request = YandexCompletionRequest(
            modelUri = modelUri,
            completionOptions = CompletionOptions(
                stream = false,
                temperature = temperature,
                //maxTokens = "2000"
            ),
            messages = messages,
            jsonObject = true,
        )

        return runCatching {
            val httpResponse: HttpResponse = client.post("https://llm.api.cloud.yandex.net/foundationModels/v1/completion") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Api-Key $apiKey")
                setBody(request)
            }

            val responseText = httpResponse.bodyAsText()

            // Проверяем статус ответа
            if (!httpResponse.status.isSuccess()) {
                // Пытаемся распарсить как ошибку
                val errorResponse = runCatching {
                    jsonParser.decodeFromString<YandexApiError>(responseText)
                }.getOrNull()

                val errorMessage = if (errorResponse != null) {
                    val details = errorResponse.error.details?.joinToString("; ") { it.message ?: "" } ?: ""
                    val fullMessage = errorResponse.error.message + if (details.isNotBlank()) " ($details)" else ""

                    // Специальная обработка ошибки превышения токенов
                    if (fullMessage.contains("context length", ignoreCase = true) ||
                        fullMessage.contains("too many tokens", ignoreCase = true) ||
                        fullMessage.contains("exceeds", ignoreCase = true)) {
                        "⚠️ Превышен лимит токенов! Файл или сообщение слишком большое. Попробуйте уменьшить размер контента."
                    } else {
                        "Ошибка API: $fullMessage"
                    }
                } else {
                    "Ошибка API (${httpResponse.status.value}): $responseText"
                }

                println("API ERROR: $errorMessage")
                return@runCatching MessageWithUsage(
                    text = errorMessage,
                    usage = null
                )
            }

            // Парсим успешный ответ
            val response = jsonParser.decodeFromString<YandexCompletionResponse>(responseText)

            val rawText = response.result.alternatives.firstOrNull()?.message?.text
                ?: "Ошибка: пустой ответ от модели"

            // Логируем сырой ответ от модели (самое раннее место)
            println("RAW API RESPONSE: $rawText")
            println("TOKEN USAGE: input=${response.result.usage.inputTextTokens}, output=${response.result.usage.completionTokens}, total=${response.result.usage.totalTokens}")

            MessageWithUsage(
                text = rawText,
                usage = response.result.usage
            )
        }.getOrElse { e ->
            println("API ERROR: ${e.message}")
            e.printStackTrace()
            MessageWithUsage(
                text = "❌ Ошибка при обращении к API: ${e.message}",
                usage = null
            )
        }
    }

    suspend fun sendMessageWithHistory(messages: List<Message>, temperature: Double = 0.6): LLMStructuredResponse {
        val result = sendMessageWithHistoryAndUsage(messages, temperature)
        return result.response
    }

    suspend fun sendMessageWithHistoryAndUsage(messages: List<Message>, temperature: Double = 0.6): StructuredResponseWithUsage {
        val messageWithUsage = sendMessageWithUsage(messages, temperature)

        val structuredResponse = runCatching {
            // Парсим JSON
            println("resp = ${messageWithUsage.text}")
            jsonParser.decodeFromString<LLMStructuredResponse>(messageWithUsage.text)
        }.getOrElse {
            // Если не удалось распарсить, возвращаем как есть
            LLMStructuredResponse(
                title = "Ошибка парсинга",
                message = messageWithUsage.text
            )
        }

        return StructuredResponseWithUsage(
            response = structuredResponse,
            usage = messageWithUsage.usage
        )
    }

    fun close() {
        client.close()
    }
}
