package kz.shprot

import kotlinx.serialization.json.Json
import kz.shprot.models.*
import java.util.*

/**
 * Управляет multi-agent системой для обработки сложных вопросов
 *
 * Принцип работы:
 * 1. Координатор анализирует вопрос и определяет нужны ли специалисты
 * 2. Если нужны - создаются агенты-специалисты (физик, экономист, математик и т.д.)
 * 3. Каждый агент отвечает последовательно, видя ответы предыдущих агентов
 * 4. Координатор синтезирует финальный ответ из всех консультаций
 */
class AgentManager(
    private val apiKey: String,
    private val modelUri: String,
    private val chatHistory: ChatHistory
) {
    private val baseClient = YandexLLMClient(apiKey, modelUri)
    private val jsonParser = Json { ignoreUnknownKeys = true }

    // Вспомогательная функция для суммирования Usage
    private fun sumUsage(usages: List<Usage?>): Usage? {
        val validUsages = usages.filterNotNull()
        if (validUsages.isEmpty()) return null

        var totalInput = 0
        var totalCompletion = 0
        var totalTokens = 0

        validUsages.forEach { usage ->
            totalInput += usage.inputTextTokens.toIntOrNull() ?: 0
            totalCompletion += usage.completionTokens.toIntOrNull() ?: 0
            totalTokens += usage.totalTokens.toIntOrNull() ?: 0
        }

        return Usage(
            inputTextTokens = totalInput.toString(),
            completionTokens = totalCompletion.toString(),
            totalTokens = totalTokens.toString()
        )
    }

    /**
     * Анализирует вопрос и определяет нужны ли специалисты
     */
    suspend fun analyzeQuestion(
        userMessage: String,
        history: List<Message>,
        temperature: Double = 0.6
    ): AgentAnalysis {
        val analysisPrompt = """
            Проанализируй вопрос пользователя и определи, нужны ли для ответа специализированные агенты-эксперты.

            КРИТЕРИИ ДЛЯ СОЗДАНИЯ СПЕЦИАЛИСТОВ:
            1. Вопрос требует глубокой экспертизы из разных областей (физика + экономика, медицина + психология и т.д.)
            2. Вопрос сложный, многоаспектный и междисциплинарный
            3. Вопрос явно запрашивает мнения разных специалистов

            НЕ СОЗДАВАЙ СПЕЦИАЛИСТОВ если:
            1. Простой фактический вопрос (математика, даты, определения)
            2. Вопрос из одной узкой области
            3. Уточняющий вопрос в диалоге
            4. Пользователь уже общается в рамках простого диалога

            ТИПЫ СПЕЦИАЛИСТОВ (примеры):
            - Научные: физик, химик, биолог, математик, астроном
            - Медицинские: терапевт, хирург, офтальмолог, кардиолог, психолог
            - Профессиональные: экономист, юрист, программист, инженер, архитектор
            - Гуманитарные: историк, философ, социолог, лингвист

            Вопрос пользователя: "$userMessage"

            Ответь в формате JSON:
            {
              "needsSpecialists": true/false,
              "complexity": "simple/medium/complex",
              "specialists": [
                {
                  "role": "название роли (например: Физик)",
                  "specialization": "область экспертизы (например: квантовая механика)",
                  "reason": "почему нужен этот специалист"
                }
              ],
              "reasoning": "объяснение почему нужны или не нужны специалисты"
            }
        """.trimIndent()

        val messages = buildList {
            add(Message(role = "system", text = analysisPrompt))
            // Добавляем краткую историю для контекста (последние 3 сообщения)
            addAll(history.takeLast(3))
            add(Message(role = "user", text = userMessage))
        }

        val rawResponse = baseClient.sendMessage(messages, temperature)
        println("ANALYSIS RESPONSE (температура $temperature): $rawResponse")

        return runCatching {
            jsonParser.decodeFromString<AgentAnalysis>(rawResponse)
        }.getOrElse { e ->
            println("Failed to parse analysis: ${e.message}")
            // Fallback: если не удалось распарсить - работаем как простой агент
            AgentAnalysis(
                needsSpecialists = false,
                complexity = "simple",
                specialists = emptyList(),
                reasoning = "Ошибка анализа, используем простой режим"
            )
        }
    }

    /**
     * Создает агента-специалиста с расширенным system prompt
     */
    fun createAgent(
        role: String,
        specialization: String,
        temperature: Double = 0.6
    ): Agent {
        val baseSystemPrompt = chatHistory.getSystemPrompt()

        val specializedPrompt = """
            $baseSystemPrompt

            ## ТВОЯ СПЕЦИАЛИЗАЦИЯ:

            Ты - **$role** со специализацией в области "$specialization".

            Отвечай на вопрос С ТОЧКИ ЗРЕНИЯ СВОЕЙ СПЕЦИАЛИЗАЦИИ:
            - Используй профессиональную терминологию когда это уместно
            - Давай экспертную оценку в своей области
            - Если другие специалисты уже высказались - учитывай их мнение и дополняй своим экспертным взглядом
            - Будь конкретным и практичным

            ВАЖНО: Отвечай в том же JSON формате {"title":"...","message":"..."}
        """.trimIndent()

        val agent = Agent(
            id = UUID.randomUUID().toString(),
            role = role,
            systemPrompt = specializedPrompt,
            temperature = temperature
        )

        println("Создан агент '$role', температура $temperature")

        return agent
    }

    /**
     * Консультация с одним агентом-специалистом
     */
    suspend fun consultAgent(
        agent: Agent,
        userMessage: String,
        history: List<Message>,
        previousResponses: List<AgentResponse>
    ): AgentResponse {
        println("📋 AGENT ${agent.role} - История для консультации:")
        println("   Количество сообщений в history: ${history.size}")
        history.forEachIndexed { index, msg ->
            val preview = msg.text.take(100).replace("\n", " ")
            println("   [$index] role=${msg.role}, text_preview='$preview...'")
        }

        val messages = buildList {
            // System prompt специалиста
            add(Message(role = "system", text = agent.systemPrompt))

            // История чата
            addAll(history)

            // Ответы предыдущих специалистов (для последовательной консультации)
            if (previousResponses.isNotEmpty()) {
                val consultationsText = previousResponses.joinToString("\n\n") {
                    "**${it.agentRole}** (специалист):\n${it.content}"
                }
                add(Message(
                    role = "system",
                    text = "Другие специалисты уже высказались:\n\n$consultationsText\n\nТеперь твоя очередь дать экспертную оценку."
                ))
            }

            // Вопрос пользователя
            add(Message(role = "user", text = userMessage))
        }

        val messageWithUsage = baseClient.sendMessageWithUsage(messages, agent.temperature)
        println("AGENT ${agent.role} (температура ${agent.temperature}) RAW RESPONSE: ${messageWithUsage.text}")

        // Парсим структурированный ответ агента
        val structuredResponse = runCatching {
            jsonParser.decodeFromString<LLMStructuredResponse>(messageWithUsage.text)
        }.getOrElse {
            LLMStructuredResponse(
                title = agent.role,
                message = messageWithUsage.text
            )
        }

        return AgentResponse(
            agentId = agent.id,
            agentRole = agent.role,
            content = structuredResponse.message,
            timestamp = System.currentTimeMillis(),
            usage = messageWithUsage.usage
        )
    }

    /**
     * Синтезирует финальный ответ из консультаций всех специалистов
     */
    suspend fun synthesizeResponse(
        userMessage: String,
        agentResponses: List<AgentResponse>,
        history: List<Message>,
        temperature: Double = 0.6
    ): StructuredResponseWithUsage {
        val synthesisPrompt = """
            Ты - координатор команды специалистов. Твоя задача - собрать финальный ответ из консультаций экспертов.

            Вопрос пользователя: "$userMessage"

            Консультации специалистов:
            ${agentResponses.joinToString("\n\n") {
                "### ${it.agentRole}\n${it.content}"
            }}

            ТВОЯ ЗАДАЧА:
            1. Проанализируй мнения всех специалистов
            2. Сформируй связный, структурированный финальный ответ
            3. Если есть разные точки зрения - покажи их
            4. Дай практические рекомендации на основе экспертных мнений
            5. Укажи в начале, что это результат консультации специалистов

            Ответь в формате JSON: {"title":"краткий заголовок","message":"полный финальный ответ"}
        """.trimIndent()

        val messages = buildList {
            add(Message(role = "system", text = synthesisPrompt))
            addAll(history.takeLast(2)) // Краткий контекст
            add(Message(role = "user", text = "Собери финальный ответ"))
        }

        val result = baseClient.sendMessageWithHistoryAndUsage(messages, temperature)
        println("SYNTHESIS RESPONSE (температура $temperature): ${result.response.message}")

        return result
    }

    /**
     * Главный метод обработки сообщения (с автоматическим анализом + поддержкой явного запроса)
     */
    suspend fun processMessage(
        chatId: Int,
        userMessage: String,
        history: List<Message>,
        temperature: Double = 0.6,
        compressContext: Boolean = false,
        compressSystemPrompt: Boolean = false,
        ragContext: String? = null,
        enrichedMessages: List<Message>? = null  // Обогащенные RAG сообщения
    ): MultiAgentResponse {
        // Проверяем явный запрос на создание специалистов
        val explicitRequest = detectExplicitAgentRequest(userMessage)

        val analysis = if (explicitRequest != null) {
            // Пользователь явно запросил специалистов
            println("Explicit agent request detected: ${explicitRequest.specialists}")
            explicitRequest
        } else {
            // Автоматический анализ
            analyzeQuestion(userMessage, history, temperature)
        }

        if (!analysis.needsSpecialists) {
            // Простой ответ от базового агента
            println("Используется базовый агент, температура $temperature")
            val messages = if (compressContext) {
                chatHistory.buildMessagesWithCompression(
                    chatId, userMessage, compressContext, compressSystemPrompt
                )
            } else {
                chatHistory.buildMessagesWithHistory(chatId, userMessage, ragContext)
            }
            val result = baseClient.sendMessageWithHistoryAndUsage(
                messages,
                temperature
            )
            return MultiAgentResponse(
                isMultiAgent = false,
                agentResponses = emptyList(),
                synthesis = result.response.message,
                title = result.response.title,
                totalUsage = result.usage
            )
        }

        println("Creating ${analysis.specialists.size} specialists for complex question")

        // Создаем специалистов
        val agents = analysis.specialists.map { spec ->
            createAgent(spec.role, spec.specialization, temperature)
        }

        // Последовательная консультация
        val agentResponses = mutableListOf<AgentResponse>()
        // Используем enrichedMessages если доступны, иначе history
        val messagesForAgents = enrichedMessages?.filter { it.role != "user" } ?: history

        println("=== Подготовка сообщений для агентов ===")
        println("enrichedMessages != null: ${enrichedMessages != null}")
        if (enrichedMessages != null) {
            println("enrichedMessages.size: ${enrichedMessages.size}")
            enrichedMessages.forEachIndexed { index, msg ->
                val preview = msg.text.take(80).replace("\n", " ")
                println("  enrichedMessages[$index]: role=${msg.role}, preview='$preview...'")
            }
        }
        println("messagesForAgents.size: ${messagesForAgents.size}")
        messagesForAgents.forEachIndexed { index, msg ->
            val preview = msg.text.take(80).replace("\n", " ")
            println("  messagesForAgents[$index]: role=${msg.role}, preview='$preview...'")
        }

        for (agent in agents) {
            println("Consulting ${agent.role}...")
            val response = consultAgent(agent, userMessage, messagesForAgents, agentResponses)
            agentResponses.add(response)
        }

        // Синтез финального ответа
        val synthesisResult = synthesizeResponse(userMessage, agentResponses, history, temperature)

        // Суммируем все Usage (от анализа, агентов и синтеза)
        val allUsages = agentResponses.mapNotNull { it.usage } + listOfNotNull(synthesisResult.usage)
        val totalUsage = sumUsage(allUsages)

        return MultiAgentResponse(
            isMultiAgent = true,
            agentResponses = agentResponses,
            synthesis = synthesisResult.response.message,
            title = synthesisResult.response.title,
            totalUsage = totalUsage
        )
    }

    /**
     * Определяет явный запрос пользователя на создание специалистов
     * Примеры: "создай физика и математика", "позови экономиста и юриста"
     */
    private fun detectExplicitAgentRequest(userMessage: String): AgentAnalysis? {
        val lowerMessage = userMessage.lowercase()

        // Ключевые фразы для явного запроса
        val requestPhrases = listOf(
            "созда.*агент", "созда.*специалист", "позов.*специалист",
            "нужен.*специалист", "хочу.*специалист", "создай.*эксперт",
            "позови.*эксперт", "нужен.*эксперт"
        )

        val hasExplicitRequest = requestPhrases.any { phrase ->
            lowerMessage.contains(Regex(phrase))
        }

        if (!hasExplicitRequest) return null

        // Пытаемся извлечь названия специалистов из текста
        val specialistTypes = listOf(
            "физик" to "физика",
            "математик" to "математика",
            "экономист" to "экономика",
            "юрист" to "право",
            "программист" to "программирование",
            "врач" to "медицина",
            "терапевт" to "общая медицина",
            "хирург" to "хирургия",
            "психолог" to "психология",
            "инженер" to "инженерия",
            "архитектор" to "архитектура",
            "историк" to "история",
            "философ" to "философия"
        )

        val foundSpecialists = specialistTypes.filter { (role, _) ->
            lowerMessage.contains(role)
        }.map { (role, spec) ->
            SpecialistInfo(
                role = role.replaceFirstChar { it.uppercase() },
                specialization = spec,
                reason = "Явный запрос пользователя"
            )
        }

        return if (foundSpecialists.isNotEmpty()) {
            AgentAnalysis(
                needsSpecialists = true,
                complexity = "complex",
                specialists = foundSpecialists,
                reasoning = "Пользователь явно запросил создание специалистов"
            )
        } else {
            null
        }
    }

    fun close() {
        baseClient.close()
    }
}
