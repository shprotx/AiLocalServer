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
import io.ktor.utils.io.*
import kz.shprot.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * Helper функция для конвертации параметров из запроса в RAGConfig
 */
fun buildRAGConfig(filterMode: String, useReranking: Boolean): RAGManager.RAGConfig {
    val filteringConfig = when (filterMode) {
        "strict" -> VectorSearchManager.FilteringConfig.STRICT
        "lenient" -> VectorSearchManager.FilteringConfig.LENIENT
        else -> VectorSearchManager.FilteringConfig.DEFAULT
    }

    return RAGManager.RAGConfig(
        filteringConfig = filteringConfig,
        useReranking = useReranking,
        rerankingTopK = 5
    )
}

/**
 * Helper функция для конвертации FilteringStats в API модель
 */
fun toFilteringStatsData(stats: FilteringStats?): RAGFilteringStatsData? {
    if (stats == null) return null
    return RAGFilteringStatsData(
        totalChunks = stats.totalChunks,
        afterPrimaryFilter = stats.afterPrimaryFilter,
        afterSmartFilter = stats.afterSmartFilter,
        finalResults = stats.finalResults,
        avgSimilarityBefore = stats.avgSimilarityBefore,
        avgSimilarityAfter = stats.avgSimilarityAfter,
        minSimilarity = stats.minSimilarity,
        maxSimilarity = stats.maxSimilarity,
        processingTimeMs = stats.processingTimeMs
    )
}

/**
 * Helper функция для конвертации RerankingStats в API модель
 */
fun toRerankingStatsData(stats: RerankingStats?): RAGRerankingStatsData? {
    if (stats == null) return null
    return RAGRerankingStatsData(
        totalCandidates = stats.totalCandidates,
        rerankedCount = stats.rerankedCount,
        avgScoreBefore = stats.avgScoreBefore,
        avgScoreAfter = stats.avgScoreAfter,
        scoreImprovement = stats.scoreImprovement,
        processingTimeMs = stats.processingTimeMs
    )
}

fun main() {
    val apiKey = System.getenv("YANDEX_API_KEY")
    val folderId = System.getenv("YANDEX_FOLDER_ID")
    val modelType = "yandexgpt-lite"  // По умолчанию полная модель

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

    // Инициализация RAG системы (база знаний) с гибридной фильтрацией и reranking
    val ollamaClient = OllamaClient(
        embeddingModel = "bge-m3",          // Модель для генерации эмбеддингов
        rerankingModel = "nomic-embed-text"  // Модель для reranking
    )
    val documentProcessor = DocumentProcessor(chunkSize = 1000, overlap = 200)
    val embeddingsManager = EmbeddingsManager(ollamaClient, db, documentProcessor)
    val vectorSearchManager = VectorSearchManager(db)  // Теперь без фиксированных параметров
    val rerankingManager = RerankingManager(ollamaClient)  // Новый менеджер для reranking
    val ragManager = RAGManager(embeddingsManager, vectorSearchManager, rerankingManager)

    val contextCompressor = ContextCompressor(llmClient)
    val agentManager = AgentManager(apiKey, modelUri, chatHistory)

    // MCP Manager для подключения внешних инструментов
    val mcpManager = SimpleMcpManager()

    println("=== Локальный сервер для общения с Yandex LLM ===")
    println("База данных: chats.db")
    println("Модель: $modelType")
    println("JSON Schema: ${if (modelType == "yandexgpt") "включена" else "отключена (lite модель)"}")
    println("Multi-Agent система: включена")
    println("RAG/База знаний: включена (Ollama + nomic-embed-text)")
    println("MCP серверы: см. mcp-servers.json")
    println("Сервер запускается на http://localhost:8080")
    println("Откройте браузер и перейдите по этому адресу")
    println()

    // Запускаем MCP серверы в отдельной корутине
    kotlinx.coroutines.runBlocking {
        try {
            mcpManager.startAllServers()
        } catch (e: Exception) {
            println("⚠️ Не удалось запустить MCP серверы: ${e.message}")
        }
    }

    // MCP Tool Handler для обработки вызовов инструментов
    val mcpToolHandler = McpToolHandler(mcpManager, llmClient)

    // Daily Summary Scheduler для автоматического создания сводок
    val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val dailySummaryScheduler = DailySummaryScheduler(
        mcpManager = mcpManager,
        llmClient = llmClient,
        mcpToolHandler = mcpToolHandler,
        chatHistory = chatHistory,
        systemChatId = 1
    )

    println("📅 Запускаем планировщик Daily Summary...")
    dailySummaryScheduler.start(schedulerScope)

    // MCP Orchestrator для автоматической композиции инструментов
    val mcpOrchestrator = McpOrchestrator(
        mcpManager = mcpManager,
        llmClient = llmClient,
        maxIterations = 15
    )
    println("🎯 MCP Orchestrator инициализирован")

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

                // 📚 RAG ПОДДЕРЖКА: Обогащаем запрос контекстом из базы знаний
                println("=== Проверка базы знаний (RAG) ===")
                println("Флаг useRAG: ${request.useRAG}")

                var baseMessages = if (request.compressContext) {
                    chatHistory.buildMessagesWithCompression(
                        request.chatId, request.message, request.compressContext, request.compressSystemPrompt
                    )
                } else {
                    listOf(Message("system", chatHistory.getSystemPrompt())) +
                    history +
                    listOf(Message("user", request.message))
                }

                // Пытаемся обогатить контекстом из базы знаний (только если useRAG включен)
                var ragEnrichmentInfo: RAGManager.RAGEnrichmentInfo? = null

                if (request.useRAG) {
                    val ragConfig = buildRAGConfig(request.ragFilterMode, request.useReranking)
                    ragEnrichmentInfo = ragManager.augmentPromptWithKnowledgeDetailed(
                        userQuery = request.message,
                        originalMessages = baseMessages,
                        config = ragConfig
                    )

                    if (ragEnrichmentInfo.ragUsed) {
                        println("✅ Запрос обогащен контекстом из базы знаний")
                        baseMessages = ragEnrichmentInfo.augmentedMessages
                    } else {
                        println("ℹ️ База знаний не использовалась (нет релевантного контекста или Ollama недоступна)")
                    }
                } else {
                    println("ℹ️ RAG отключен пользователем (useRAG=false)")
                }

                // 🔧 MCP ПОДДЕРЖКА: Сначала пробуем обработать через MCP инструменты
                // Это позволяет быстро отвечать на простые запросы с использованием инструментов
                val mcpSystemPrompt = McpSystemPromptBuilder.buildSystemPrompt(mcpManager)
                val messagesForMcp = listOf(Message("system", mcpSystemPrompt)) + baseMessages.drop(1)

                println("=== Проверка на MCP tool_calls ===")
                val mcpCheckResponse = llmClient.sendMessageWithHistoryAndUsage(
                    messages = messagesForMcp,
                    temperature = request.temperature ?: 0.6
                )

                // Если LLM запросил вызов инструментов - обрабатываем их
                if (!mcpCheckResponse.response.tool_calls.isNullOrEmpty()) {
                    println("🔧 Обнаружены tool_calls (${mcpCheckResponse.response.tool_calls!!.size}), обрабатываем через MCP")

                    val toolCallResult = mcpToolHandler.handleToolCalls(
                        llmResponse = mcpCheckResponse.response,
                        conversationHistory = messagesForMcp,
                        temperature = request.temperature ?: 0.6
                    )

                    // Конвертируем Usage в TokenUsageInfo
                    val tokenInfo = usageToTokenInfo(mcpCheckResponse.usage, modelType)

                    // Вычисляем использование контекстного окна
                    val contextWindowUsage = mcpCheckResponse.usage?.let { usage ->
                        val inputTokens = usage.inputTextTokens.toIntOrNull() ?: 0
                        val isActuallyCompressed = request.compressContext &&
                            chatHistory.getCompressionInfo(request.chatId) != null
                        chatHistory.calculateContextWindowUsage(
                            chatId = request.chatId,
                            currentRequestTokens = inputTokens,
                            isCompressed = isActuallyCompressed
                        )
                    }

                    // Сохраняем сообщения в истории
                    chatHistory.addMessage(request.chatId, "user", request.message)
                    chatHistory.addMessage(request.chatId, "assistant", toolCallResult.response.message, mcpCheckResponse.usage)

                    // Возвращаем ответ от MCP с информацией об использованных инструментах
                    val mcpResponse = ChatResponse(
                        response = toolCallResult.response.message,
                        title = toolCallResult.response.title,
                        isMultiAgent = false,
                        agents = null,
                        tokenUsage = tokenInfo,
                        contextWindowUsage = contextWindowUsage,
                        usedTools = toolCallResult.usedTools.takeIf { it.isNotEmpty() }, // Передаем использованные инструменты
                        ragUsed = ragEnrichmentInfo?.ragUsed ?: false,
                        ragContext = ragEnrichmentInfo?.ragContext,
                        ragChunksCount = ragEnrichmentInfo?.chunksCount,
                        ragFilteringStats = toFilteringStatsData(ragEnrichmentInfo?.filteringStats),
                        ragRerankingStats = toRerankingStatsData(ragEnrichmentInfo?.rerankingStats)
                    )

                    call.respond(mcpResponse)
                    return@post
                }

                println("=== MCP tool не требуется, используем multi-agent систему ===")

                // Обрабатываем сообщение через multi-agent систему
                // ВАЖНО: Используем baseMessages (уже обогащенные RAG контекстом)
                val historyForAgents = baseMessages.filter { it.role != "user" }
                val multiAgentResponse = agentManager.processMessage(
                    chatId = request.chatId,
                    userMessage = request.message,
                    history = historyForAgents,
                    temperature = request.temperature ?: 0.6,
                    compressContext = request.compressContext,
                    compressSystemPrompt = request.compressSystemPrompt,
                    ragContext = ragEnrichmentInfo?.ragContext
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
                        contextWindowUsage = contextWindowUsage,
                        ragUsed = ragEnrichmentInfo?.ragUsed ?: false,
                        ragContext = ragEnrichmentInfo?.ragContext,
                        ragChunksCount = ragEnrichmentInfo?.chunksCount,
                        ragFilteringStats = toFilteringStatsData(ragEnrichmentInfo?.filteringStats),
                        ragRerankingStats = toRerankingStatsData(ragEnrichmentInfo?.rerankingStats)
                    )
                } else {
                    ChatResponse(
                        response = multiAgentResponse.synthesis,
                        title = multiAgentResponse.title,
                        isMultiAgent = false,
                        agents = null,
                        tokenUsage = tokenInfo,
                        contextWindowUsage = contextWindowUsage,
                        ragUsed = ragEnrichmentInfo?.ragUsed ?: false,
                        ragContext = ragEnrichmentInfo?.ragContext,
                        ragChunksCount = ragEnrichmentInfo?.chunksCount,
                        ragFilteringStats = toFilteringStatsData(ragEnrichmentInfo?.filteringStats),
                        ragRerankingStats = toRerankingStatsData(ragEnrichmentInfo?.rerankingStats)
                    )
                }

                call.respond(response)
            }

            // Endpoint для сравнения ответов с RAG и без RAG
            post("/api/chat/compare") {
                val request = call.receive<CompareRequest>()
                println("=== Запрос на сравнение (с/без RAG) ===")
                println("Message: ${request.message}")
                println("ChatId: ${request.chatId}")

                // Загружаем чат в память (если еще не загружен)
                chatHistory.loadChat(request.chatId)

                // ⚠️ Для сравнения используем ПУСТУЮ историю - изолированный запрос
                // Сравнение должно быть чистым: только один вопрос с RAG и без RAG
                val history = emptyList<Message>()

                // Базовые сообщения (без истории чата)
                val baseMessages = listOf(
                    Message("system", chatHistory.getSystemPrompt()),
                    Message("user", request.message)
                )

                // ========== ЗАПРОС С RAG ==========
                println("=== Выполняется запрос С RAG ===")
                val ragConfig = buildRAGConfig(request.ragFilterMode, request.useReranking)
                val ragEnrichmentInfo = ragManager.augmentPromptWithKnowledgeDetailed(
                    userQuery = request.message,
                    originalMessages = baseMessages,
                    config = ragConfig
                )

                val messagesWithRAG = if (ragEnrichmentInfo.ragUsed) {
                    println("✅ RAG включен: найдено ${ragEnrichmentInfo.chunksCount} чанков")
                    ragEnrichmentInfo.augmentedMessages
                } else {
                    println("⚠️ RAG не сработал (нет контекста)")
                    baseMessages
                }

                // Запрос к LLM с RAG (БЕЗ MCP, для чистого сравнения)
                val multiAgentResponseWithRAG = agentManager.processMessage(
                    chatId = request.chatId,
                    userMessage = request.message,
                    history = history,
                    temperature = request.temperature ?: 0.6,
                    compressContext = request.compressContext,
                    compressSystemPrompt = request.compressSystemPrompt,
                    ragContext = ragEnrichmentInfo.ragContext
                )

                // ========== ЗАПРОС БЕЗ RAG ==========
                println("=== Выполняется запрос БЕЗ RAG ===")
                val multiAgentResponseWithoutRAG = agentManager.processMessage(
                    chatId = request.chatId,
                    userMessage = request.message,
                    history = history,
                    temperature = request.temperature ?: 0.6,
                    compressContext = request.compressContext,
                    compressSystemPrompt = request.compressSystemPrompt,
                    ragContext = null // Явно НЕ передаем RAG контекст
                )

                // Формируем ответы
                val tokenInfoWithRAG = usageToTokenInfo(multiAgentResponseWithRAG.totalUsage, modelType)
                val tokenInfoWithoutRAG = usageToTokenInfo(multiAgentResponseWithoutRAG.totalUsage, modelType)

                val contextWindowUsageWithRAG = multiAgentResponseWithRAG.totalUsage?.let { usage ->
                    val inputTokens = usage.inputTextTokens.toIntOrNull() ?: 0
                    val isActuallyCompressed = request.compressContext &&
                        chatHistory.getCompressionInfo(request.chatId) != null
                    chatHistory.calculateContextWindowUsage(
                        chatId = request.chatId,
                        currentRequestTokens = inputTokens,
                        isCompressed = isActuallyCompressed
                    )
                }

                val contextWindowUsageWithoutRAG = multiAgentResponseWithoutRAG.totalUsage?.let { usage ->
                    val inputTokens = usage.inputTextTokens.toIntOrNull() ?: 0
                    val isActuallyCompressed = request.compressContext &&
                        chatHistory.getCompressionInfo(request.chatId) != null
                    chatHistory.calculateContextWindowUsage(
                        chatId = request.chatId,
                        currentRequestTokens = inputTokens,
                        isCompressed = isActuallyCompressed
                    )
                }

                val responseWithRAG = ChatResponse(
                    response = multiAgentResponseWithRAG.synthesis,
                    title = multiAgentResponseWithRAG.title,
                    isMultiAgent = multiAgentResponseWithRAG.isMultiAgent,
                    agents = if (multiAgentResponseWithRAG.isMultiAgent) {
                        multiAgentResponseWithRAG.agentResponses.map {
                            kz.shprot.models.AgentResponseData(
                                role = it.agentRole,
                                content = it.content
                            )
                        }
                    } else null,
                    tokenUsage = tokenInfoWithRAG,
                    contextWindowUsage = contextWindowUsageWithRAG,
                    ragUsed = ragEnrichmentInfo.ragUsed,
                    ragContext = ragEnrichmentInfo.ragContext,
                    ragChunksCount = ragEnrichmentInfo.chunksCount,
                    ragFilteringStats = toFilteringStatsData(ragEnrichmentInfo.filteringStats),
                    ragRerankingStats = toRerankingStatsData(ragEnrichmentInfo.rerankingStats)
                )

                val responseWithoutRAG = ChatResponse(
                    response = multiAgentResponseWithoutRAG.synthesis,
                    title = multiAgentResponseWithoutRAG.title,
                    isMultiAgent = multiAgentResponseWithoutRAG.isMultiAgent,
                    agents = if (multiAgentResponseWithoutRAG.isMultiAgent) {
                        multiAgentResponseWithoutRAG.agentResponses.map {
                            kz.shprot.models.AgentResponseData(
                                role = it.agentRole,
                                content = it.content
                            )
                        }
                    } else null,
                    tokenUsage = tokenInfoWithoutRAG,
                    contextWindowUsage = contextWindowUsageWithoutRAG,
                    ragUsed = false,
                    ragContext = null
                )

                // Сохраняем в историю чата
                val compareResponse = CompareResponse(
                    withRAG = responseWithRAG,
                    withoutRAG = responseWithoutRAG,
                    ragContext = ragEnrichmentInfo.ragContext,
                    ragChunksCount = ragEnrichmentInfo.chunksCount,
                    similarityScores = ragEnrichmentInfo.similarityScores,
                    filteringStats = toFilteringStatsData(ragEnrichmentInfo.filteringStats),
                    rerankingStats = toRerankingStatsData(ragEnrichmentInfo.rerankingStats)
                )

                // Сериализуем сравнение в JSON и сохраняем как специальное сообщение
                val jsonParser = kotlinx.serialization.json.Json {
                    prettyPrint = false
                    ignoreUnknownKeys = true
                }
                val comparisonJson = jsonParser.encodeToString(CompareResponse.serializer(), compareResponse)

                // Сохраняем сообщение пользователя и результат сравнения
                chatHistory.addMessage(request.chatId, "user", request.message)
                // Добавляем префикс __COMPARISON__ чтобы фронтенд знал что это сравнение
                chatHistory.addMessage(request.chatId, "assistant", "__COMPARISON__$comparisonJson", multiAgentResponseWithRAG.totalUsage)

                println("✅ Сравнение сохранено в историю чата")

                call.respond(compareResponse)
            }

            // Тестовый endpoint для проверки MCP
            get("/api/mcp/test") {
                try {
                    val result = mcpManager.callTool(
                        toolName = "get_current_temperature",
                        arguments = mapOf(
                            "latitude" to 55.7558,
                            "longitude" to 37.6173
                        )
                    )
                    call.respondText("MCP Test: $result", ContentType.Text.Plain)
                } catch (e: Exception) {
                    call.respondText("MCP Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                }
            }

            // Получение списка доступных MCP инструментов (для отладки)
            get("/api/mcp-tools") {
                val tools = mcpManager.listAllToolsDetailed()
                call.respond(mapOf(
                    "tools" to tools.map { mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "parameters" to it.parameters,
                        "server" to it.serverName
                    )}
                ))
            }

            // Ручной запуск Daily Summary (для тестирования)
            post("/api/daily-summary/run") {
                try {
                    println("🔧 Ручной запуск Daily Summary...")
                    dailySummaryScheduler.runManually()
                    call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Daily summary создан успешно"))
                } catch (e: Exception) {
                    println("❌ Ошибка при создании daily summary: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Упрощенный endpoint для чата с MCP инструментами (без multi-agent)
            post("/api/chat/with-mcp") {
                val request = call.receive<ChatRequest>()
                println("=== MCP Chat Request ===")
                println("Message: ${request.message}")
                println("ChatId: ${request.chatId}")

                // Загружаем чат
                chatHistory.loadChat(request.chatId)

                // Добавляем сообщение пользователя
                chatHistory.addMessage(request.chatId, "user", request.message)

                // Строим system prompt с MCP инструментами
                val systemPromptWithMcp = McpSystemPromptBuilder.buildSystemPrompt(mcpManager)

                // Получаем историю
                val history = chatHistory.getMessages(request.chatId)

                // Строим сообщения для LLM
                val messages = listOf(Message("system", systemPromptWithMcp)) + history

                // Первый запрос к LLM
                val firstResponse = llmClient.sendMessageWithHistoryAndUsage(
                    messages = messages,
                    temperature = request.temperature ?: 0.6
                )

                println("=== First LLM Response ===")
                println("Title: ${firstResponse.response.title}")
                println("Tool calls: ${firstResponse.response.tool_calls?.map { it.name }}")

                // Обрабатываем tool_calls если есть
                val toolCallResult = mcpToolHandler.handleToolCalls(
                    llmResponse = firstResponse.response,
                    conversationHistory = messages,
                    temperature = request.temperature ?: 0.6
                )

                // Сохраняем финальный ответ
                chatHistory.addMessage(request.chatId, "assistant", toolCallResult.response.message)

                // Формируем ответ с информацией об использованных инструментах
                val response = ChatResponse(
                    response = toolCallResult.response.message,
                    title = toolCallResult.response.title,
                    isMultiAgent = false,
                    agents = null,
                    tokenUsage = usageToTokenInfo(firstResponse.usage, modelType),
                    contextWindowUsage = null,
                    usedTools = toolCallResult.usedTools.takeIf { it.isNotEmpty() }
                )

                call.respond(response)
            }

            // MCP Orchestrator - автоматическая композиция инструментов
            post("/api/mcp-orchestrator") {
                val request = call.receive<OrchestratorRequest>()
                println("=== MCP Orchestrator Request ===")
                println("Task: ${request.task}")
                println("Temperature: ${request.temperature}")

                try {
                    // Выполняем задачу через оркестратор
                    val result = mcpOrchestrator.executeTask(
                        userRequest = request.task,
                        temperature = request.temperature ?: 0.6
                    )

                    // Формируем ответ
                    val response = OrchestratorResponse(
                        success = result.success,
                        finalAnswer = result.finalAnswer,
                        toolCalls = result.toolCalls.map { toolCall ->
                            ToolCallInfo(
                                iteration = toolCall.iteration,
                                toolName = toolCall.toolName,
                                parameters = toolCall.parameters.toString(),
                                result = toolCall.result.take(500) // Ограничиваем длину для JSON
                            )
                        },
                        iterations = result.iterations
                    )

                    call.respond(response)
                } catch (e: Exception) {
                    println("❌ Ошибка в MCP Orchestrator: ${e.message}")
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            // ==================== RAG / Knowledge Base Endpoints ====================

            // Загрузка файла в базу знаний
            post("/api/knowledge/upload") {
                try {
                    val multipart = call.receiveMultipart()
                    var filename = ""
                    var fileBytes: ByteArray? = null

                    var part = multipart.readPart()
                    while (part != null) {
                        when (part) {
                            is io.ktor.http.content.PartData.FileItem -> {
                                filename = part.originalFileName ?: "unnamed"
                                fileBytes = part.provider().toByteArray()
                            }
                            else -> {}
                        }
                        part.dispose()
                        part = multipart.readPart()
                    }

                    if (fileBytes == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
                        return@post
                    }

                    println("📤 Загрузка файла: $filename")

                    // Обработка и сохранение документа
                    val documentId = embeddingsManager.processAndStoreDocument(
                        fileContent = fileBytes!!.inputStream(),
                        filename = filename
                    )

                    call.respond(HttpStatusCode.Created, UploadFileResponse(
                        success = true,
                        documentId = documentId,
                        filename = filename,
                        message = "Файл успешно загружен и обработан"
                    ))
                } catch (e: Exception) {
                    println("❌ Ошибка при загрузке файла: ${e.message}")
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(error = e.message ?: "Unknown error")
                    )
                }
            }

            // Получение списка документов в базе знаний
            get("/api/knowledge/documents") {
                try {
                    val stats = embeddingsManager.getKnowledgeBaseStats()
                    call.respond(KnowledgeBaseStatsResponse(
                        totalDocuments = stats.totalDocuments,
                        totalChunks = stats.totalChunks,
                        documents = stats.documents.map { doc ->
                            DocumentInfo(
                                id = doc.id,
                                filename = doc.filename,
                                fileType = doc.fileType,
                                uploadDate = doc.uploadDate,
                                totalChunks = doc.totalChunks
                            )
                        }
                    ))
                } catch (e: Exception) {
                    println("❌ Ошибка при получении списка документов: ${e.message}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(error = e.message ?: "Unknown error")
                    )
                }
            }

            // Удаление документа из базы знаний
            delete("/api/knowledge/documents/{id}") {
                try {
                    val documentId = call.parameters["id"]?.toIntOrNull()
                    if (documentId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid document ID"))
                        return@delete
                    }

                    val deleted = embeddingsManager.deleteDocument(documentId)
                    if (deleted) {
                        call.respond(DeleteDocumentResponse(success = true, message = "Документ удален"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Document not found"))
                    }
                } catch (e: Exception) {
                    println("❌ Ошибка при удалении документа: ${e.message}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(error = e.message ?: "Unknown error")
                    )
                }
            }
        }
    }.also { server ->
        // Graceful shutdown для MCP серверов и планировщика
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\n🛑 Останавливаем Daily Summary планировщик...")
            dailySummaryScheduler.stop()

            println("🛑 Останавливаем MCP серверы...")
            kotlinx.coroutines.runBlocking {
                mcpManager.stopAllServers()
            }
            server.stop(1000, 2000)
        })
    }.start(wait = true)
}
