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
    val apiKey = System.getenv("DEEPSEEK_API_KEY")
    val model = System.getenv("MODEL") ?: "deepseek-chat"  // По умолчанию deepseek-chat

    if (apiKey.isNullOrBlank()) {
        println("Ошибка: Необходимо установить переменные окружения:")
        println("  - DEEPSEEK_API_KEY (ваш API ключ DeepSeek)")
        println("  - MODEL (опционально: deepseek-chat, deepseek-reasoner, по умолчанию deepseek-chat)")
        return
    }

    val chatHistory = ChatHistory()
    val agentManager = AgentManager(apiKey, model, chatHistory)

    println("=== Локальный сервер для общения с DeepSeek ===")
    println("🤖 Модель: $model")
    println("📋 JSON Schema: включена")
    println("👥 Multi-Agent система: включена")
    println("🌡️  Контроль температуры: включен")
    println("🚀 Сервер запускается на http://localhost:8080")
    println("🌐 Откройте браузер и перейдите по этому адресу")
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
