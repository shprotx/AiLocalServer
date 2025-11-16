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

    // Инициализация базы данных
    val db = DatabaseManager("chats.db")
    val chatHistory = ChatHistory(db)

    val contextCompressor = ContextCompressor(llmClient)
    val agentManager = AgentManager(apiKey, modelUri, chatHistory)

    println("=== Локальный сервер для общения с Yandex LLM ===")
    println("База данных: chats.db")
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

            // Получение списка всех чатов
            get("/api/chats") {
                val chats = chatHistory.getAllChats()
                val response = ChatListResponse(
                    chats = chats.map { chatData ->
                        Chat(
                            id = chatData.id,
                            title = chatData.title,
                            createdAt = chatData.createdAt,
                            updatedAt = chatData.updatedAt
                        )
                    }
                )
                call.respond(response)
            }

            // Создание нового чата
            post("/api/chats") {
                val request = call.receive<CreateChatRequest>()
                val chatId = chatHistory.createChat(request.title)
                val response = CreateChatResponse(chatId = chatId, title = request.title)
                call.respond(HttpStatusCode.Created, response)
            }

            // Удаление чата
            delete("/api/chats/{id}") {
                val chatId = call.parameters["id"]?.toIntOrNull()
                if (chatId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid chat ID"))
                    return@delete
                }

                val deleted = chatHistory.deleteChat(chatId)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
                }
            }

            // Получение истории сообщений чата
            get("/api/chats/{id}/messages") {
                val chatId = call.parameters["id"]?.toIntOrNull()
                if (chatId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid chat ID"))
                    return@get
                }

                val messages = db.getMessages(chatId)
                val response = MessagesResponse(
                    messages = messages.map { msgData ->
                        ChatMessage(
                            id = msgData.id,
                            chatId = msgData.chatId,
                            role = msgData.role,
                            content = msgData.content,
                            timestamp = msgData.timestamp
                        )
                    }
                )
                call.respond(response)
            }

            // Обычный режим чата (с multi-agent поддержкой)
            post("/api/chat") {
                val request = call.receive<ChatRequest>()
                println("=== Получен запрос ===")
                println("Message: ${request.message}")
                println("ChatId: ${request.chatId}")
                println("Temperature: ${request.temperature}")
                println("Compress Context: ${request.compressContext}")
                println("Compress System Prompt: ${request.compressSystemPrompt}")

                // Загружаем чат в память (если еще не загружен)
                chatHistory.loadChat(request.chatId)

                // Получаем историю сообщений для контекста
                val history = chatHistory.getMessages(request.chatId)

                // Обработка сжатия контекста (если включено)
                println("=== Проверка сжатия ===")
                println("Тумблер compressContext: ${request.compressContext}")
                println("Количество сообщений в истории: ${history.size}")

                if (request.compressContext && history.size >= 10) {
                    val currentCompression = chatHistory.getCompressionInfo(request.chatId)
                    val needsCompression = currentCompression == null ||
                        (history.size - (currentCompression.compressedUpToIndex + 1)) >= 10

                    println("Есть текущее сжатие: ${currentCompression != null}")
                    if (currentCompression != null) {
                        println("Сжато до индекса: ${currentCompression.compressedUpToIndex}")
                    }
                    println("Нужно сжатие: $needsCompression")

                    if (needsCompression) {
                        println("=== Выполняется сжатие контекста ===")
                        println("Количество сообщений в истории: ${history.size}")

                        // Создаем или обновляем сжатие
                        val newCompression = contextCompressor.createOrUpdateCompression(
                            currentMessages = history,
                            existingCompression = currentCompression,
                            keepLastN = 1,  // Оставляем только последнее сообщение
                            temperature = 0.3
                        )

                        // Сжимаем системный промпт (если нужно и еще не сжат)
                        if (request.compressSystemPrompt && newCompression != null &&
                            newCompression.compressedSystemPrompt == null) {
                            println("=== Выполняется сжатие системного промпта ===")
                            val compressedPrompt = contextCompressor.compressSystemPrompt(
                                chatHistory.getSystemPrompt(),
                                temperature = 0.3
                            )
                            chatHistory.updateCompressionInfo(
                                request.chatId,
                                newCompression.copy(compressedSystemPrompt = compressedPrompt)
                            )
                        } else if (newCompression != null) {
                            chatHistory.updateCompressionInfo(request.chatId, newCompression)
                        }

                        println("=== Сжатие контекста завершено ===")
                    }
                } else {
                    println("Условие для сжатия НЕ выполнено (compressContext=${request.compressContext}, history.size=${history.size})")
                }

                // Проверяем, будет ли использоваться сжатие в этом запросе
                val compressionExists = chatHistory.getCompressionInfo(request.chatId)
                println("=== Использование сжатия в текущем запросе ===")
                println("Сжатие существует: ${compressionExists != null}")
                println("Будет использовано: ${request.compressContext && compressionExists != null}")

                // Обрабатываем сообщение через multi-agent систему
                val multiAgentResponse = agentManager.processMessage(
                    chatId = request.chatId,
                    userMessage = request.message,
                    history = history,
                    temperature = request.temperature ?: 0.6,
                    compressContext = request.compressContext,
                    compressSystemPrompt = request.compressSystemPrompt
                )

                // Конвертируем Usage в TokenUsageInfo
                val tokenInfo = usageToTokenInfo(multiAgentResponse.totalUsage, modelType)

                // Вычисляем использование контекстного окна
                val contextWindowUsage = multiAgentResponse.totalUsage?.let { usage ->
                    val inputTokens = usage.inputTextTokens.toIntOrNull() ?: 0
                    // Проверяем, действительно ли сжатие используется (не просто включен тумблер)
                    val isActuallyCompressed = request.compressContext &&
                        chatHistory.getCompressionInfo(request.chatId) != null
                    chatHistory.calculateContextWindowUsage(
                        chatId = request.chatId,
                        currentRequestTokens = inputTokens,
                        isCompressed = isActuallyCompressed
                    )
                }

                // Проверяем, не является ли ответ ошибкой
                val isError = multiAgentResponse.synthesis.startsWith("⚠️") ||
                              multiAgentResponse.synthesis.startsWith("❌") ||
                              multiAgentResponse.synthesis.contains("Ошибка API:", ignoreCase = true) ||
                              multiAgentResponse.totalUsage == null

                // Сохраняем сообщения в истории ТОЛЬКО если НЕ было ошибки
                if (!isError) {
                    chatHistory.addMessage(request.chatId, "user", request.message)
                    chatHistory.addMessage(request.chatId, "assistant", multiAgentResponse.synthesis, multiAgentResponse.totalUsage)
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
                        tokenUsage = tokenInfo,
                        contextWindowUsage = contextWindowUsage
                    )
                } else {
                    ChatResponse(
                        response = multiAgentResponse.synthesis,
                        title = multiAgentResponse.title,
                        isMultiAgent = false,
                        agents = null,
                        tokenUsage = tokenInfo,
                        contextWindowUsage = contextWindowUsage
                    )
                }

                call.respond(response)
            }
        }
    }.start(wait = true)
}
