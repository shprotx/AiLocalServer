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

class OpenRouterClient(
    private val apiKey: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    private val endpoint = "https://openrouter.ai/api/v1/chat/completions"

    /**
     * Отправляет запрос к конкретной модели на OpenRouter
     * Возвращает детальную информацию о запросе включая метрики
     */
    suspend fun sendMessageWithMetrics(
        modelId: String,
        messages: List<OpenRouterMessage>,
        temperature: Double = 0.7
    ): OpenRouterDetailedResponse = runCatching {
        val startTime = System.currentTimeMillis()

        val request = OpenRouterRequest(
            model = modelId,
            messages = messages,
            temperature = temperature,
            maxTokens = 2000
        )

        val response: OpenRouterResponse = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            header("HTTP-Referer", "http://localhost:8080")
            header("X-Title", "AI Local Server - Model Comparison")
            setBody(request)
        }.body()

        val endTime = System.currentTimeMillis()
        val responseTime = endTime - startTime

        // Проверяем на ошибку от OpenRouter
        if (response.error != null) {
            println("OpenRouter API ERROR: ${response.error.message}")
            return OpenRouterDetailedResponse(
                modelId = modelId,
                content = "Ошибка API: ${response.error.message}",
                responseTimeMs = responseTime,
                inputTokens = 0,
                outputTokens = 0,
                totalTokens = 0,
                estimatedCost = null,
                finishReason = "error"
            )
        }

        val firstChoice = response.choices?.firstOrNull()
        val content = firstChoice?.message?.content ?: "Пустой ответ от модели"

        val inputTokens = response.usage?.promptTokens ?: 0
        val outputTokens = response.usage?.completionTokens ?: 0
        val totalTokens = response.usage?.totalTokens ?: 0

        // OpenRouter возвращает стоимость в usage (если доступно)
        val estimatedCost = calculateCost(modelId, inputTokens, outputTokens)

        OpenRouterDetailedResponse(
            modelId = modelId,
            content = content,
            responseTimeMs = responseTime,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            estimatedCost = estimatedCost,
            finishReason = firstChoice?.finishReason
        )
    }.getOrElse { e ->
        println("OpenRouter API ERROR for model $modelId: ${e.message}")
        e.printStackTrace()
        OpenRouterDetailedResponse(
            modelId = modelId,
            content = "Ошибка при обращении к модели: ${e.message}",
            responseTimeMs = 0,
            inputTokens = 0,
            outputTokens = 0,
            totalTokens = 0,
            estimatedCost = null,
            finishReason = "error"
        )
    }

    /**
     * Примерный расчет стоимости на основе известных цен моделей
     * В идеале OpenRouter сам возвращает стоимость в usage
     */
    private fun calculateCost(modelId: String, inputTokens: Int, outputTokens: Int): Double? {
        // Примерные цены (в USD за 1M токенов)
        // Источник: https://openrouter.ai/models
        val pricing = when {
            // Топовые модели (70B+)
            modelId.contains("llama-3.1-70b", ignoreCase = true) -> Pair(0.35, 0.40)
            modelId.contains("llama-3-70b", ignoreCase = true) -> Pair(0.59, 0.79)
            modelId.contains("mixtral-8x7b", ignoreCase = true) -> Pair(0.24, 0.24)
            modelId.contains("qwen-2.5", ignoreCase = true) -> Pair(0.30, 0.30)
            // Крошечные модели (1-3B) - почти бесплатные
            modelId.contains("llama-3.2-1b", ignoreCase = true) -> Pair(0.04, 0.04)
            modelId.contains("llama-3.2-3b", ignoreCase = true) -> Pair(0.06, 0.06)
            // Остальные
            modelId.contains("mistral-7b", ignoreCase = true) -> Pair(0.06, 0.06)
            modelId.contains("qwen", ignoreCase = true) -> Pair(0.18, 0.18)
            modelId.contains("gpt-3.5", ignoreCase = true) -> Pair(0.50, 1.50)
            modelId.contains("gpt-4", ignoreCase = true) -> Pair(30.0, 60.0)
            else -> return null // Неизвестная модель
        }

        val (inputPricePerM, outputPricePerM) = pricing
        val inputCost = (inputTokens / 1_000_000.0) * inputPricePerM
        val outputCost = (outputTokens / 1_000_000.0) * outputPricePerM

        return inputCost + outputCost
    }

    fun close() {
        client.close()
    }
}
