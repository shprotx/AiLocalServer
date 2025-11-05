package kz.shprot

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kz.shprot.models.*

class YandexLLMClient(
    private val apiKey: String,
    private val folderId: String,
    private val modelUri: String = "gpt://$folderId/yandexgpt-lite/latest"
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    suspend fun sendMessage(messages: List<Message>): String {
        val request = YandexCompletionRequest(
            modelUri = modelUri,
            completionOptions = CompletionOptions(
                stream = false,
                temperature = 0.6,
                maxTokens = "2000"
            ),
            messages = messages
        )

        return try {
            val response: YandexCompletionResponse = client.post("https://llm.api.cloud.yandex.net/foundationModels/v1/completion") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Api-Key $apiKey")
                setBody(request)
            }.body()

            response.result.alternatives.firstOrNull()?.message?.text
                ?: "Ошибка: пустой ответ от модели"
        } catch (e: Exception) {
            "Ошибка при обращении к API: ${e.message}"
        }
    }

    suspend fun sendMessageWithHistory(messages: List<Message>): LLMStructuredResponse {
        val rawResponse = sendMessage(messages)

        return try {
            // Пытаемся извлечь JSON из ответа
            val jsonText = extractJsonFromResponse(rawResponse)
            Json.decodeFromString<LLMStructuredResponse>(jsonText)
        } catch (e: Exception) {
            // Если не удалось распарсить JSON, возвращаем ответ как есть
            LLMStructuredResponse(
                title = "Ответ",
                message = rawResponse
            )
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        // Ищем JSON объект в ответе

        println("response = $response")
        val jsonStart = response.indexOf("{")
        val jsonEnd = response.lastIndexOf("}") + 1

        return if (jsonStart != -1 && jsonEnd > jsonStart) {
            response.substring(jsonStart, jsonEnd)
        } else {
            response
        }
    }

    fun close() {
        client.close()
    }
}
