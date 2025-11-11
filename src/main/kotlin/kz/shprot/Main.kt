package kz.shprot

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.shprot.models.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

fun main() {
    val apiKey = System.getenv("YANDEX_API_KEY")
    val folderId = System.getenv("YANDEX_FOLDER_ID")
    val modelType = "yandexgpt"  // По умолчанию полная модель

    if (apiKey.isNullOrBlank() || folderId.isNullOrBlank()) {
        println("Ошибка: Необходимо установить переменные окружения:")
        println("  - YANDEX_API_KEY (ваш API ключ)")
        println("  - YANDEX_FOLDER_ID (ID вашей папки в Yandex Cloud)")
        println("  - MODEL_TYPE (опционально: yandexgpt или yandexgpt-lite, по умолчанию yandexgpt)")
        return
    }

    val modelUri = "gpt://$folderId/$modelType/latest"
    //val llmClient = YandexLLMClient(apiKey, modelUri)
    val chatHistory = ChatHistory()
    val agentManager = AgentManager(apiKey, modelUri, chatHistory)

    // OpenRouter клиент для сравнения моделей (опционально)
    val openRouterApiKey = System.getenv("OPENROUTER_API_KEY")
    val openRouterClient = if (!openRouterApiKey.isNullOrBlank()) {
        OpenRouterClient(openRouterApiKey)
    } else {
        null
    }

    println("=== Локальный сервер для общения с Yandex LLM ===")
    println("Модель: $modelType")
    println("JSON Schema: ${if (modelType == "yandexgpt") "включена" else "отключена (lite модель)"}")
    println("Multi-Agent система: включена")
    println("Model Comparison: ${if (openRouterClient != null) "включено" else "отключено (нет OPENROUTER_API_KEY)"}")
    println("Сервер запускается на http://localhost:8080")
    println("Откройте браузер и перейдите по этому адресу")
    println()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }

        routing {
            get("/") {
                val htmlContent = File("src/main/resources/static/index.html").readText()
                call.respondText(htmlContent, ContentType.Text.Html)
            }

            post("/api/chat") {
                val request = call.receive<ChatRequest>()
                println("=== Получен запрос ===")
                println("Message: ${request.message}")
                println("Temperature: ${request.temperature}")
                println("SessionId: ${request.sessionId}")

                // Получаем историю сообщений для контекста
                val history = chatHistory.getMessages(request.sessionId)

                // Обрабатываем сообщение через multi-agent систему
                val multiAgentResponse = agentManager.processMessage(
                    sessionId = request.sessionId,
                    userMessage = request.message,
                    history = history,
                    temperature = request.temperature ?: 0.6
                )

                // Сохраняем сообщения в истории
                chatHistory.addMessage(request.sessionId, "user", request.message)
                chatHistory.addMessage(request.sessionId, "assistant", multiAgentResponse.synthesis)

                // Преобразуем в ChatResponse
                val response = if (multiAgentResponse.isMultiAgent) {
                    ChatResponse(
                        response = multiAgentResponse.synthesis,
                        title = multiAgentResponse.title,
                        isMultiAgent = true,
                        agents = multiAgentResponse.agentResponses.map {
                            kz.shprot.models.AgentResponseData(
                                role = it.agentRole,
                                content = it.content
                            )
                        }
                    )
                } else {
                    ChatResponse(
                        response = multiAgentResponse.synthesis,
                        title = multiAgentResponse.title,
                        isMultiAgent = false,
                        agents = null
                    )
                }

                call.respond(response)
            }

            // Endpoint для сравнения моделей
            post("/api/chat/compare") {
                if (openRouterClient == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "OpenRouter API key не установлен. Добавьте OPENROUTER_API_KEY в переменные окружения.")
                    )
                    return@post
                }

                val request = call.receive<ModelComparisonRequest>()
                println("=== Сравнение моделей ===")
                println("Message: ${request.message}")
                println("Models: ${request.models}")
                println("Temperature: ${request.temperature}")

                val startTime = System.currentTimeMillis()

                // Параллельно запрашиваем все модели
                val results = coroutineScope {
                    request.models.map { modelId ->
                        async {
                            val messages = listOf(
                                OpenRouterMessage(role = "system", content = "Ты - полезный ассистент."),
                                OpenRouterMessage(role = "user", content = request.message)
                            )

                            val detailedResponse = openRouterClient.sendMessageWithMetrics(
                                modelId = modelId,
                                messages = messages,
                                temperature = request.temperature
                            )

                            // Преобразуем в ModelComparisonResult
                            ModelComparisonResult(
                                modelId = modelId,
                                modelName = PresetModels.getDisplayName(modelId),
                                response = detailedResponse.content,
                                responseTimeMs = detailedResponse.responseTimeMs,
                                inputTokens = detailedResponse.inputTokens,
                                outputTokens = detailedResponse.outputTokens,
                                totalTokens = detailedResponse.totalTokens,
                                estimatedCost = detailedResponse.estimatedCost,
                                estimatedCostFormatted = formatCost(detailedResponse.estimatedCost),
                                status = if (detailedResponse.finishReason == "error") "error" else "success"
                            )
                        }
                    }.map { it.await() }
                }

                val totalTime = System.currentTimeMillis() - startTime

                val response = ModelComparisonResponse(
                    results = results,
                    totalTimeMs = totalTime
                )

                println("Сравнение завершено за ${totalTime}ms")
                call.respond(response)
            }
        }
    }.start(wait = true)
}

// Вспомогательная функция для форматирования стоимости
private fun formatCost(cost: Double?): String = when {
    cost == null -> "неизвестно"
    cost == 0.0 -> "бесплатно"
    cost < 0.0001 -> String.format("$%.6f", cost)
    cost < 0.01 -> String.format("$%.4f", cost)
    else -> String.format("$%.2f", cost)
}
