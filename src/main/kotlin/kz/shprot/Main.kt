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
import kz.shprot.models.ChatRequest
import kz.shprot.models.ChatResponse
import java.io.File

// Вспомогательная функция для расчета стоимости
fun calculateCost(totalTokens: Int, modelType: String): Double {
    val costPer1000 = when (modelType) {
        "yandexgpt" -> 0.80 // 0.80 руб за 1000 токенов для полной модели
        "yandexgpt-lite" -> 0.16 // 0.16 руб за 1000 токенов для lite
        else -> 0.50 // Default fallback
    }
    return (totalTokens / 1000.0) * costPer1000
}

// Конвертация Usage в TokenUsageInfo
fun usageToTokenInfo(usage: kz.shprot.models.Usage?, modelType: String): kz.shprot.models.TokenUsageInfo? {
    usage ?: return null

    val inputTokens = usage.inputTextTokens.toIntOrNull() ?: 0
    val outputTokens = usage.completionTokens.toIntOrNull() ?: 0
    val totalTokens = usage.totalTokens.toIntOrNull() ?: 0
    val cost = calculateCost(totalTokens, modelType)

    return kz.shprot.models.TokenUsageInfo(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        estimatedCostRub = cost,
        modelName = modelType
    )
}

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
    val llmClient = YandexLLMClient(apiKey, modelUri)
    val chatHistory = ChatHistory()
    val agentManager = AgentManager(apiKey, modelUri, chatHistory)

    println("=== Локальный сервер для общения с Yandex LLM ===")
    println("Модель: $modelType")
    println("JSON Schema: ${if (modelType == "yandexgpt") "включена" else "отключена (lite модель)"}")
    println("Multi-Agent система: включена")
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

                // Конвертируем Usage в TokenUsageInfo
                val tokenInfo = usageToTokenInfo(multiAgentResponse.totalUsage, modelType)

                // Проверяем, не является ли ответ ошибкой
                val isError = multiAgentResponse.synthesis.startsWith("⚠️") ||
                              multiAgentResponse.synthesis.startsWith("❌") ||
                              multiAgentResponse.synthesis.contains("Ошибка API:", ignoreCase = true) ||
                              multiAgentResponse.totalUsage == null

                // Сохраняем сообщения в истории ТОЛЬКО если НЕ было ошибки
                // Иначе огромное сообщение с файлом будет отправляться снова и снова
                if (!isError) {
                    chatHistory.addMessage(request.sessionId, "user", request.message)
                    chatHistory.addMessage(request.sessionId, "assistant", multiAgentResponse.synthesis, multiAgentResponse.totalUsage)
                } else {
                    println("⚠️ Ошибка API - сообщение НЕ сохранено в историю чата")
                }

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
                        },
                        tokenUsage = tokenInfo
                    )
                } else {
                    ChatResponse(
                        response = multiAgentResponse.synthesis,
                        title = multiAgentResponse.title,
                        isMultiAgent = false,
                        agents = null,
                        tokenUsage = tokenInfo
                    )
                }

                call.respond(response)
            }
        }
    }.start(wait = true)
}
