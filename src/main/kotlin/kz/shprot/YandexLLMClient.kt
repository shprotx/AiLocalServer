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
    private val modelUri: String,
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
            messages = messages,
            jsonObject = true,
        )

        return runCatching {
            val response: YandexCompletionResponse = client.post("https://llm.api.cloud.yandex.net/foundationModels/v1/completion") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Api-Key $apiKey")
                setBody(request)
            }.body()

            val rawText = response.result.alternatives.firstOrNull()?.message?.text
                ?: "Ошибка: пустой ответ от модели"

            // Логируем сырой ответ от модели (самое раннее место)
            println("RAW API RESPONSE: $rawText")

            rawText
        }.getOrElse { e ->
            println("API ERROR: ${e.message}")
            "Ошибка при обращении к API: ${e.message}"
        }
    }

    suspend fun sendMessageWithHistory(messages: List<Message>): LLMStructuredResponse {
        val rawResponse = sendMessage(messages)

        return runCatching {
            // Парсим JSON
            println("resp = $rawResponse")
            Json.decodeFromString<LLMStructuredResponse>(rawResponse)
        }.getOrElse {
            // Если не удалось распарсить, возвращаем как есть
            LLMStructuredResponse(
                title = "Ошибка парсинга",
                message = rawResponse
            )
        }
    }

    fun close() {
        client.close()
    }
}
