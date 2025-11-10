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
        }
    }.start(wait = true)
}
