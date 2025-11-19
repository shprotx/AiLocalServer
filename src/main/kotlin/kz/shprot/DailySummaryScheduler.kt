package kz.shprot

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Планировщик для автоматического создания daily summary из Telegram каналов
 */
class DailySummaryScheduler(
    private val mcpManager: SimpleMcpManager,
    private val llmClient: YandexLLMClient,
    private val mcpToolHandler: McpToolHandler,
    private val chatHistory: ChatHistory,
    private val systemChatId: Int = 1 // ID системного чата для daily summaries
) {
    private val logger = LoggerFactory.getLogger(DailySummaryScheduler::class.java)
    private var schedulerJob: Job? = null

    // Настройки
    private val targetChannelName = "Mobile Dev Jobs" // Название канала для мониторинга
    private val summaryIntervalMinutes = 10L // Интервал в минутах (для тестирования)
    private val temperature = 0.6

    /**
     * Запускает планировщик
     */
    fun start(scope: CoroutineScope) {
        logger.info("📅 Запуск планировщика daily summary")
        logger.info("   Канал: $targetChannelName")
        logger.info("   Интервал: каждые $summaryIntervalMinutes минут")
        logger.info("   Системный чат ID: $systemChatId")

        schedulerJob = scope.launch {
            // Создаем системный чат если его нет
            ensureSystemChatExists()

            // Запускаем первый summary сразу (для теста)
            logger.info("🚀 Первый запуск создания daily summary...")
            try {
                createDailySummary()
            } catch (e: Exception) {
                logger.error("❌ Ошибка создания daily summary: ${e.message}", e)
            }

            // Затем запускаем по расписанию
            while (isActive) {
                val delayMs = summaryIntervalMinutes * 60 * 1000
                logger.info("⏰ Следующий запуск daily summary через $summaryIntervalMinutes минут")

                delay(delayMs)

                // Выполняем daily summary
                try {
                    logger.info("🚀 Запуск создания daily summary...")
                    createDailySummary()
                } catch (e: Exception) {
                    logger.error("❌ Ошибка создания daily summary: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Останавливает планировщик
     */
    fun stop() {
        logger.info("🛑 Остановка планировщика daily summary")
        schedulerJob?.cancel()
        schedulerJob = null
    }

    /**
     * Создает системный чат если его нет
     */
    private suspend fun ensureSystemChatExists() {
        withContext(Dispatchers.IO) {
            if (!chatHistory.chatExists(systemChatId)) {
                logger.info("📝 Создание системного чата для daily summaries")
                chatHistory.createChatWithId(
                    id = systemChatId,
                    title = "📰 Daily Summaries"
                )
            }
        }
    }

    /**
     * Создает daily summary и сохраняет в системный чат
     */
    private suspend fun createDailySummary() {
        logger.info("📊 Формирование daily summary из канала '$targetChannelName'")

        // Шаг 1: Получаем список диалогов
        logger.info("   Шаг 1/3: Получение списка диалогов...")
        val dialogsResult = mcpManager.callTool("tg_dialogs", emptyMap())

        // Шаг 2: Находим нужный канал и получаем сообщения
        logger.info("   Шаг 2/3: Получение сообщений из канала...")

        // Парсим результат tg_dialogs для поиска канала
        val channelName = findChannelName(dialogsResult, targetChannelName)

        if (channelName == null) {
            logger.warn("⚠️ Канал '$targetChannelName' не найден в списке диалогов")

            // Сохраняем уведомление об ошибке
            chatHistory.addMessage(
                systemChatId,
                "assistant",
                "⚠️ Не удалось найти канал '$targetChannelName' для создания daily summary"
            )
            return
        }

        val messagesResult = mcpManager.callTool(
            "tg_dialog",
            mapOf("name" to channelName, "limit" to 10) // Ограничиваем до 10 последних сообщений
        )

        // Шаг 3: Формируем summary через LLM
        logger.info("   Шаг 3/3: Формирование summary через LLM...")

        // Парсим сообщения для более читабельного формата
        val messagesText = try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(messagesResult).jsonObject
            val messages = result["messages"]?.jsonArray ?: buildJsonArray {  }

            messages.take(10).joinToString("\n\n") { msg ->
                val obj = msg.jsonObject
                val who = obj["who"]?.jsonPrimitive?.content ?: "Unknown"
                val text = obj["text"]?.jsonPrimitive?.content ?: ""
                val when_ = obj["when"]?.jsonPrimitive?.content ?: ""
                "[$when_] $who:\n$text"
            }
        } catch (e: Exception) {
            logger.error("❌ Ошибка парсинга сообщений: ${e.message}")
            messagesResult // Используем как есть если не удалось распарсить
        }

        logger.info("   Размер текста для анализа: ${messagesText.length} символов")

        val summaryPrompt = """
Ты - аналитик новостей. Проанализируй сообщения из Telegram канала с вакансиями для мобильных разработчиков.

Сообщения за последнее время:
$messagesText

Твоя задача - создать краткое резюме (2-4 абзаца):

1. **Основные направления**: какие технологии и платформы чаще всего встречаются (iOS/Android/Flutter/React Native)
2. **Популярные навыки**: какие стеки и инструменты требуются работодателям
3. **Уровень зарплат**: диапазон зарплатных ожиданий и предложений
4. **Формат работы**: удаленка, офис, гибрид

Отвечай обычным текстом с markdown форматированием (без JSON).
        """.trimIndent()

        val summaryResponse = try {
            llmClient.sendMessage(
                messages = listOf(
                    kz.shprot.models.Message("system", "Ты - профессиональный аналитик рынка труда IT специалистов."),
                    kz.shprot.models.Message("user", summaryPrompt)
                ),
                temperature = temperature,
                useJsonSchema = false  // ВАЖНО: отключаем JSON Schema для получения обычного текста
            )
        } catch (e: Exception) {
            logger.error("❌ Ошибка вызова LLM: ${e.message}", e)
            chatHistory.addMessage(
                systemChatId,
                "assistant",
                "⚠️ Ошибка при создании summary: ${e.message}"
            )
            return
        }

        logger.info("   LLM ответ получен (${summaryResponse.length} символов): ${summaryResponse.take(100)}...")

        // Проверяем что ответ не пустой
        if (summaryResponse.isBlank() || summaryResponse.trim() == "{}" || summaryResponse.length < 50) {
            logger.error("❌ LLM вернул пустой или слишком короткий ответ!")
            chatHistory.addMessage(
                systemChatId,
                "assistant",
                "⚠️ Не удалось создать summary - LLM вернул некорректный ответ (${summaryResponse.length} символов)."
            )
            return
        }

        // Сохраняем summary в системный чат
        val currentDate = LocalDateTime.now().toLocalDate()
        val summaryMessage = """
# 📰 Daily Summary: $targetChannelName
**Дата:** $currentDate

$summaryResponse

---
_Автоматически сгенерировано в ${LocalDateTime.now()}_
        """.trimIndent()

        chatHistory.addMessage(
            systemChatId,
            "assistant",
            summaryMessage
        )

        logger.info("✅ Daily summary успешно создан и сохранен в чат #$systemChatId")
    }

    /**
     * Находит имя канала в результатах tg_dialogs
     */
    private fun findChannelName(dialogsJson: String, targetTitle: String): String? {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val dialogsResult = json.parseToJsonElement(dialogsJson).jsonObject
            val dialogs = dialogsResult["dialogs"]?.jsonArray

            dialogs?.forEach { dialog ->
                val obj = dialog.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content
                val name = obj["name"]?.jsonPrimitive?.content

                if (title != null && title.contains(targetTitle, ignoreCase = true)) {
                    logger.info("   ✅ Найден канал: $title (name: $name)")
                    return name ?: title
                }
            }
        } catch (e: Exception) {
            logger.error("❌ Ошибка парсинга результатов tg_dialogs: ${e.message}")
        }
        return null
    }

    /**
     * Ручной запуск создания summary (для тестирования)
     */
    suspend fun runManually() {
        logger.info("🔧 Ручной запуск создания daily summary")
        createDailySummary()
    }
}
