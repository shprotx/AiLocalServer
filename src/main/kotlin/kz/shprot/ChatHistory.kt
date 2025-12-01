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
 * Контекст чата с информацией о сжатии (в памяти)
 */
data class ChatContext(
    val messages: MutableList<MessageWithTokens> = mutableListOf(),
    var compressionInfo: CompressionInfo? = null
)

/**
 * Управление историей чатов с персистентным хранилищем в SQLite
 */
class ChatHistory(private val db: DatabaseManager) {
    // Кэш загруженных чатов в памяти для быстрого доступа
    private val chatCache = ConcurrentHashMap<Int, ChatContext>()

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
        return """Ты - полезный AI-ассистент, который ВЫПОЛНЯЕТ задачи, а не бесконечно уточняет.
            |
            |## ГЛАВНЫЙ ПРИНЦИП: ДЕЙСТВУЙ, А НЕ СПРАШИВАЙ
            |
            |В 80% случаев ты должен СРАЗУ выполнять задачу. Уточняющие вопросы - это ИСКЛЮЧЕНИЕ, а не правило.
            |
            |## КОГДА ОТВЕЧАТЬ СРАЗУ (без вопросов):
            |
            |1. **Творческие задачи** - стихи, рассказы, тексты, песни → ПИШИ СРАЗУ
            |2. **Факты** - математика, даты, определения, история → ОТВЕЧАЙ СРАЗУ
            |3. **Общие инструкции** - как что-то работает → ОБЪЯСНЯЙ СРАЗУ
            |4. **Код** - написать функцию, исправить баг → ПИШИ СРАЗУ
            |5. **Перевод, объяснение, анализ** → ДЕЛАЙ СРАЗУ
            |6. **Если пользователь говорит "пофиг", "любой", "без разницы", "да"** → ПРЕКРАТИ СПРАШИВАТЬ И ДЕЙСТВУЙ
            |
            |## КОГДА МОЖНО УТОЧНИТЬ (редко, максимум 1-2 раза за диалог):
            |
            |**Только для критически важной информации:**
            |- Ремонт техники → марка, модель, симптомы (но НЕ всё сразу, 1-2 вопроса)
            |- Медицинские вопросы → симптомы, длительность
            |- Покупка дорогой техники → бюджет, основные требования
            |
            |**НЕ УТОЧНЯЙ:**
            |- Настроение стихотворения (просто выбери сам)
            |- Стиль текста (если не указан - выбери подходящий)
            |- Мелкие детали, которые можно решить самому
            |
            |## ПРАВИЛО ОДНОГО УТОЧНЕНИЯ
            |
            |Если ты уже задал уточняющий вопрос и пользователь ответил ЛЮБЫМ образом (даже "пофиг", "ок", "да") → ВЫПОЛНЯЙ ЗАДАЧУ.
            |Не спрашивай повторно. Один вопрос - это максимум для большинства задач.
            |
            |## Примеры:
            |
            |**Творческая задача (сразу ответ):**
            |User: "Напиши стихотворение про небо"
            |Assistant: {"title":"Стихотворение","message":"Над головой бескрайний свод,\nГде облака ведут свой ход...[ПОЛНОЕ СТИХОТВОРЕНИЕ]"}
            |
            |**Пользователь сказал "пофиг" (сразу ответ):**
            |User: "да пофиг" / "любое" / "без разницы"
            |Assistant: {"title":"[Результат]","message":"[ВЫПОЛНЕННАЯ ЗАДАЧА - стих, ответ, текст]"}
            |
            |**Технический вопрос (максимум 1-2 уточнения):**
            |User: "Машина не заводится"
            |Assistant: {"title":"Уточняющий вопрос","message":"Что происходит при повороте ключа? Стартер крутит?"}
            |
            |User: "Стартер крутит, но не схватывает"
            |Assistant: {"title":"Диагностика","message":"Раз стартер крутит, но двигатель не заводится, проверьте: 1) Топливо... 2) Свечи... [ПОЛНЫЙ ОТВЕТ]"}
            |
            |**Фактический вопрос (сразу ответ):**
            |User: "Столица Франции?"
            |Assistant: {"title":"Ответ","message":"Париж"}
            |
            |## ФОРМАТ ОТВЕТА:
            |
            |ВСЕГДА отвечай СТРОГО в формате JSON:
            |{"title":"краткий заголовок","message":"текст ответа"}
            |
            |## ИТОГО:
            |- Творческие задачи → СРАЗУ ВЫПОЛНЯЙ
            |- Факты и код → СРАЗУ ОТВЕЧАЙ
            |- "Пофиг/любой/да" от пользователя → ПРЕКРАТИ СПРАШИВАТЬ
            |- Уточнения → МАКСИМУМ 1-2 за весь диалог, только для критичных вещей
        """.trimMargin()
    }

    /**
     * Создание нового чата
     * @param title название чата (можно сгенерировать из первого сообщения)
     * @return ID созданного чата
     */
    fun createChat(title: String = "Новый чат"): Int {
        return db.createChat(title)
    }

    /**
     * Создание чата с конкретным ID (для системных чатов)
     * @param id желаемый ID чата
     * @param title название чата
     * @return true если успешно создан
     */
    fun createChatWithId(id: Int, title: String): Boolean {
        return db.createChatWithId(id, title)
    }

    /**
     * Проверка существования чата
     * @param chatId ID чата
     * @return true если чат существует
     */
    fun chatExists(chatId: Int): Boolean {
        return db.getChat(chatId) != null
    }

    /**
     * Получение всех чатов
     */
    fun getAllChats(): List<ChatData> {
        return db.getAllChats()
    }

    /**
     * Удаление чата
     */
    fun deleteChat(chatId: Int): Boolean {
        chatCache.remove(chatId)
        return db.deleteChat(chatId)
    }

    /**
     * Загрузка чата из БД в память (если еще не загружен)
     */
    fun loadChat(chatId: Int) {
        if (chatCache.containsKey(chatId)) {
            return // Уже загружен
        }

        val messagesFromDb = db.getMessages(chatId)
        val context = ChatContext()

        // Конвертируем MessageData -> MessageWithTokens
        messagesFromDb.forEach { msgData ->
            context.messages.add(
                MessageWithTokens(
                    message = Message(role = msgData.role, text = msgData.content),
                    usage = null // Токены не сохраняем в БД пока
                )
            )
        }

        chatCache[chatId] = context
        println("📥 Чат $chatId загружен в память (${context.messages.size} сообщений)")
    }

    /**
     * Добавление сообщения в чат (сохраняется и в БД, и в памяти)
     */
    fun addMessage(chatId: Int, role: String, text: String, usage: Usage? = null) {
        // Загружаем чат в память если еще не загружен
        loadChat(chatId)

        // Добавляем в память
        val context = chatCache.getOrPut(chatId) { ChatContext() }
        context.messages.add(MessageWithTokens(
            message = Message(role = role, text = text),
            usage = usage
        ))

        // Сохраняем в БД
        db.saveMessage(chatId, role, text)

        // Обновляем заголовок чата если это первое сообщение пользователя
        if (context.messages.size == 1 && role == "user") {
            val title = text.take(50) // Первые 50 символов
            db.updateChatTitle(chatId, title)
        }
    }

    /**
     * Получение сообщений чата (из памяти, с автозагрузкой из БД)
     */
    fun getMessages(chatId: Int): List<Message> {
        loadChat(chatId)
        return chatCache[chatId]?.messages?.map { it.message } ?: emptyList()
    }

    /**
     * Получение сообщений с информацией о токенах
     */
    fun getMessagesWithTokens(chatId: Int): List<MessageWithTokens> {
        loadChat(chatId)
        return chatCache[chatId]?.messages?.toList() ?: emptyList()
    }

    /**
     * Построение списка сообщений для отправки в LLM (с system prompt)
     */
    fun buildMessagesWithHistory(chatId: Int, userMessage: String, ragContext: String? = null): List<Message> {
        val messages = mutableListOf<Message>()

        // Добавляем system prompt (с RAG контекстом если есть)
        val systemPrompt = if (ragContext != null) {
            """
            ${getSystemPrompt()}

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            📚 КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            $ragContext

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            ВАЖНО: Вся информация для ответа на вопрос пользователя УЖЕ НАХОДИТСЯ ВЫШЕ в разделе "КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ".

            ИНСТРУКЦИЯ:
            1. Внимательно прочитай контекст выше
            2. Используй ТОЛЬКО эту информацию для формирования ответа
            3. НЕ переспрашивай и НЕ проси предоставить текст - он уже есть в контексте
            4. Отвечай конкретно на вопрос пользователя, используя информацию из контекста
            5. Если в контексте нет нужной информации - так и скажи, но НЕ проси текст
            """.trimIndent()
        } else {
            getSystemPrompt()
        }

        messages.add(Message(role = "system", text = systemPrompt))

        // Добавляем историю
        messages.addAll(getMessages(chatId))

        // Добавляем текущее сообщение пользователя
        messages.add(Message(role = "user", text = userMessage))

        return messages
    }

    /**
     * Получает общую статистику токенов для чата
     */
    fun getSessionStats(chatId: Int, modelUri: String): SessionTokenStats {
        val messagesWithTokens = getMessagesWithTokens(chatId)
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
     * Получает информацию о сжатии для чата
     */
    fun getCompressionInfo(chatId: Int): CompressionInfo? {
        loadChat(chatId)
        return chatCache[chatId]?.compressionInfo
    }

    /**
     * Обновляет информацию о сжатии для чата
     */
    fun updateCompressionInfo(chatId: Int, compressionInfo: CompressionInfo?) {
        loadChat(chatId)
        val context = chatCache.getOrPut(chatId) { ChatContext() }
        context.compressionInfo = compressionInfo
    }

    /**
     * Строит сообщения с учетом сжатия контекста
     *
     * @param chatId ID чата
     * @param userMessage Новое сообщение пользователя
     * @param useCompression Использовать ли сжатие
     * @param compressSystemPrompt Сжать ли системный промпт
     * @return Список сообщений для отправки в LLM
     */
    fun buildMessagesWithCompression(
        chatId: Int,
        userMessage: String,
        useCompression: Boolean,
        compressSystemPrompt: Boolean
    ): List<Message> {
        loadChat(chatId)
        val messages = mutableListOf<Message>()
        val context = chatCache[chatId]

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
            messages.addAll(getMessages(chatId))
        }

        // Добавляем текущее сообщение пользователя
        messages.add(Message(role = "user", text = userMessage))

        return messages
    }

    /**
     * Вычисляет использование контекстного окна для текущего запроса
     *
     * @param chatId ID чата
     * @param currentRequestTokens Количество токенов в текущем запросе
     * @param isCompressed Используется ли сжатие
     * @param maxContextWindow Максимальный размер контекстного окна модели
     * @return Информация об использовании контекстного окна
     */
    fun calculateContextWindowUsage(
        chatId: Int,
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

    /**
     * Очистка чата из кэша (НЕ удаляет из БД!)
     */
    fun clearChatCache(chatId: Int) {
        chatCache.remove(chatId)
    }
}
