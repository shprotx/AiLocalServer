package kz.shprot

import kz.shprot.models.Message
import kz.shprot.models.Usage
import kz.shprot.models.SessionTokenStats
import java.util.concurrent.ConcurrentHashMap

/**
 * Информация о сообщении с токенами
 */
data class MessageWithTokens(
    val message: Message,
    val usage: Usage? = null
)

/**
 * Контекст сессии с информацией о сжатии
 */
data class SessionContext(
    val messages: MutableList<MessageWithTokens> = mutableListOf(),
    var compressionInfo: CompressionInfo? = null
)

class ChatHistory {
    private val sessions = ConcurrentHashMap<String, SessionContext>()

    // Получение модели из URI для расчета стоимости
    private fun extractModelName(modelUri: String): String {
        return when {
            modelUri.contains("yandexgpt-lite") -> "yandexgpt-lite"
            modelUri.contains("yandexgpt") -> "yandexgpt"
            else -> "unknown"
        }
    }

    // Расчет стоимости в рублях
    private fun calculateCost(totalTokens: Int, modelName: String): Double {
        val costPer1000 = when (modelName) {
            "yandexgpt" -> 0.80 // 0.80 руб за 1000 токенов для полной модели
            "yandexgpt-lite" -> 0.16 // 0.16 руб за 1000 токенов для lite
            else -> 0.50 // Default fallback
        }
        return (totalTokens / 1000.0) * costPer1000
    }

    fun getSystemPrompt(): String {
        return """Ты - вдумчивый AI-ассистент и консультант, который ведет естественный диалог с пользователем.
            |
            |## КЛЮЧЕВОЙ ПРИНЦИП: ПОСТЕПЕННЫЙ СБОР ИНФОРМАЦИИ
            |
            |Когда пользователь задает сложный вопрос (требующий персонализации), ты должен:
            |1. **Задавать по 1-2 вопроса за раз** (не больше!)
            |2. **Анализировать ответы** и решать, что спросить дальше
            |3. **Вести естественный диалог**, как живой консультант
            |4. **Когда информации достаточно** - дать полный, детальный финальный ответ
            |
            |## ВАЖНО: Анализируй историю диалога!
            |
            |Перед каждым ответом:
            |- Посмотри, что ты УЖЕ СПРОСИЛ в предыдущих сообщениях
            |- Посмотри, что пользователь УЖЕ ОТВЕТИЛ
            |- НЕ повторяй вопросы, на которые уже есть ответ
            |- Задавай СЛЕДУЮЩИЙ логичный вопрос, основываясь на полученной информации
            |
            |## Когда задавать вопросы постепенно:
            |
            |**Практические задачи** (готовка, ремонт, покупки):
            |- Начни с 1-2 самых важных вопросов
            |- Подожди ответа пользователя
            |- Задай следующий вопрос, основываясь на ответе
            |- Продолжай, пока не соберешь достаточно информации
            |
            |**Рекомендации** (фильмы, книги, товары):
            |- Сначала узнай общие предпочтения (1-2 вопроса)
            |- Потом уточни детали
            |- Затем дай рекомендации
            |
            |**Советы** (здоровье, карьера, финансы):
            |- Начни с контекста (1-2 вопроса)
            |- Уточни детали ситуации
            |- Дай персонализированный совет
            |
            |## Когда отвечать сразу (БЕЗ вопросов):
            |
            |1. **Факты**: математика, даты, определения, история
            |2. **Общие инструкции**: как что-то работает в принципе
            |3. **Если пользователь уже дал ВСЮ нужную информацию** в своем вопросе
            |4. **Если ты уже собрал достаточно информации** в ходе диалога
            |
            |## Как определить, что информации достаточно:
            |
            |**Для готовки**: знаешь продукты, количество людей, время, предпочтения
            |**Для ремонта**: знаешь модель, симптомы, что пробовали
            |**Для покупок**: знаешь бюджет, цель, предпочтения
            |**Для рекомендаций**: знаешь вкусы, что уже пробовали, ограничения
            |
            |Когда информации достаточно → дай **ФИНАЛЬНЫЙ РАЗВЕРНУТЫЙ ОТВЕТ** с учетом ВСЕЙ собранной информации из диалога.
            |
            |## Примеры диалогов:
            |
            |**Пример 1: Ремонт машины**
            |User: "Как починить машину?"
            |Assistant: {"title":"Уточняющий вопрос","message":"Какая у вас марка и модель автомобиля?"}
            |
            |User: "Honda Civic"
            |Assistant: {"title":"Уточняющий вопрос","message":"Какие симптомы у машины? Что именно не работает?"}
            |
            |User: "Не заводится"
            |Assistant: {"title":"Уточняющий вопрос","message":"Проверяли ли вы свечи зажигания? Может, пробовали что-то еще?"}
            |
            |User: "Свечи выкручивал, вроде норм"
            |Assistant: {"title":"Диагностика и решение","message":"Хорошо, раз свечи в порядке, давайте проверим другие причины...[ПОЛНЫЙ ДЕТАЛЬНЫЙ ОТВЕТ с учетом Honda Civic, проблемы с запуском, проверенных свечей]"}
            |
            |**Пример 2: Готовка**
            |User: "Что приготовить на ужин?"
            |Assistant: {"title":"Уточняющий вопрос","message":"Какие продукты есть у вас в наличии?"}
            |
            |User: "Курица, рис, овощи"
            |Assistant: {"title":"Уточняющий вопрос","message":"Сколько человек будет за столом?"}
            |
            |User: "Четверо"
            |Assistant: {"title":"Уточняющий вопрос","message":"Есть ли какие-то пищевые ограничения или аллергии у кого-то?"}
            |
            |User: "Нет"
            |Assistant: {"title":"Рецепт ужина","message":"Отлично! Учитывая что у вас есть курица, рис и овощи, и что вы готовите на 4 человека...[ПОЛНЫЙ ДЕТАЛЬНЫЙ РЕЦЕПТ]"}
            |
            |**Пример 3: Фактический вопрос (сразу ответ)**
            |User: "Сколько будет 2+2?"
            |Assistant: {"title":"Ответ","message":"2 + 2 = 4"}
            |
            |User: "Когда была Октябрьская революция?"
            |Assistant: {"title":"Исторический факт","message":"Октябрьская революция произошла в 1917 году (25-26 октября по старому стилю, 7-8 ноября по новому стилю)."}
            |
            |## ФОРМАТ ОТВЕТА:
            |
            |Ты должен ВСЕГДА отвечать СТРОГО в формате JSON:
            |{"title":"краткий заголовок","message":"текст вопроса или ответа"}
            |
            |Не добавляй никаких дополнительных символов до или после JSON.
            |
            |## ВАЖНО:
            |- НЕ задавай все вопросы сразу списком!
            |- Задавай по 1-2 вопроса, жди ответа, анализируй, задавай следующий
            |- Веди ЕСТЕСТВЕННЫЙ ДИАЛОГ
            |- Когда информации достаточно - дай ПОЛНЫЙ финальный ответ
        """.trimMargin()
    }

    fun addMessage(sessionId: String, role: String, text: String, usage: Usage? = null) {
        val context = sessions.getOrPut(sessionId) { SessionContext() }
        context.messages.add(MessageWithTokens(
            message = Message(role = role, text = text),
            usage = usage
        ))
    }

    fun getMessages(sessionId: String): List<Message> {
        return sessions[sessionId]?.messages?.map { it.message } ?: emptyList()
    }

    fun getMessagesWithTokens(sessionId: String): List<MessageWithTokens> {
        return sessions[sessionId]?.messages?.toList() ?: emptyList()
    }

    fun buildMessagesWithHistory(sessionId: String, userMessage: String): List<Message> {
        val messages = mutableListOf<Message>()

        // Добавляем system prompt
        messages.add(Message(role = "system", text = getSystemPrompt()))

        // Добавляем историю
        messages.addAll(getMessages(sessionId))

        // Добавляем текущее сообщение пользователя
        messages.add(Message(role = "user", text = userMessage))

        return messages
    }

    /**
     * Получает общую статистику токенов для сессии
     */
    fun getSessionStats(sessionId: String, modelUri: String): SessionTokenStats {
        val messagesWithTokens = getMessagesWithTokens(sessionId)
        val modelName = extractModelName(modelUri)

        var totalInput = 0
        var totalOutput = 0
        var messageCount = 0

        messagesWithTokens.forEach { msgWithTokens ->
            msgWithTokens.usage?.let { usage ->
                totalInput += usage.inputTextTokens.toIntOrNull() ?: 0
                totalOutput += usage.completionTokens.toIntOrNull() ?: 0
                messageCount++
            }
        }

        val totalTokens = totalInput + totalOutput
        val totalCost = calculateCost(totalTokens, modelName)

        return SessionTokenStats(
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalTokens = totalTokens,
            totalCostRub = totalCost,
            messageCount = messageCount
        )
    }

    /**
     * Получает информацию о сжатии для сессии
     */
    fun getCompressionInfo(sessionId: String): CompressionInfo? {
        return sessions[sessionId]?.compressionInfo
    }

    /**
     * Обновляет информацию о сжатии для сессии
     */
    fun updateCompressionInfo(sessionId: String, compressionInfo: CompressionInfo?) {
        val context = sessions.getOrPut(sessionId) { SessionContext() }
        context.compressionInfo = compressionInfo
    }

    /**
     * Строит сообщения с учетом сжатия контекста
     *
     * @param sessionId ID сессии
     * @param userMessage Новое сообщение пользователя
     * @param useCompression Использовать ли сжатие
     * @param compressSystemPrompt Сжать ли системный промпт
     * @return Список сообщений для отправки в LLM
     */
    fun buildMessagesWithCompression(
        sessionId: String,
        userMessage: String,
        useCompression: Boolean,
        compressSystemPrompt: Boolean
    ): List<Message> {
        val messages = mutableListOf<Message>()
        val context = sessions[sessionId]

        // Добавляем system prompt (сжатый или полный)
        val systemPrompt = if (compressSystemPrompt && context?.compressionInfo?.compressedSystemPrompt != null) {
            context.compressionInfo!!.compressedSystemPrompt!!
        } else {
            getSystemPrompt()
        }
        messages.add(Message(role = "system", text = systemPrompt))

        // Если есть сжатие и оно включено
        if (useCompression && context?.compressionInfo != null) {
            val compression = context.compressionInfo!!

            // Добавляем сжатое резюме как системное сообщение
            messages.add(Message(
                role = "system",
                text = "Контекст предыдущего диалога:\n${compression.compressedSummary}"
            ))

            // Добавляем только несжатые сообщения (последние N)
            val allMessages = context.messages.map { it.message }
            val uncompressedMessages = allMessages.subList(
                compression.compressedUpToIndex + 1,
                allMessages.size
            )
            messages.addAll(uncompressedMessages)
        } else {
            // Используем полную историю
            messages.addAll(getMessages(sessionId))
        }

        // Добавляем текущее сообщение пользователя
        messages.add(Message(role = "user", text = userMessage))

        return messages
    }

    /**
     * Вычисляет использование контекстного окна для текущего запроса
     *
     * @param sessionId ID сессии
     * @param currentRequestTokens Количество токенов в текущем запросе
     * @param isCompressed Используется ли сжатие
     * @param maxContextWindow Максимальный размер контекстного окна модели
     * @return Информация об использовании контекстного окна
     */
    fun calculateContextWindowUsage(
        sessionId: String,
        currentRequestTokens: Int,
        isCompressed: Boolean,
        maxContextWindow: Int = 8000
    ): kz.shprot.models.ContextWindowUsage {
        val usagePercent = (currentRequestTokens.toDouble() / maxContextWindow) * 100.0

        return kz.shprot.models.ContextWindowUsage(
            currentTokens = currentRequestTokens,
            maxTokens = maxContextWindow,
            usagePercent = usagePercent.coerceIn(0.0, 100.0),
            isCompressed = isCompressed
        )
    }

    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}
