package kz.shprot

import kz.shprot.models.Message

/**
 * Информация о сжатии диалога
 */
data class CompressionInfo(
    val compressedUpToIndex: Int,      // До какого индекса (включительно) сжаты сообщения
    val compressedSummary: String,      // Сжатое резюме диалога
    val compressedSystemPrompt: String? = null  // Сжатый системный промпт (опционально)
)

/**
 * Класс для сжатия контекста диалога с помощью LLM
 */
class ContextCompressor(
    private val llmClient: YandexLLMClient
) {
    /**
     * Сжимает список сообщений в краткое резюме
     *
     * @param messages Список сообщений для сжатия
     * @param temperature Температура генерации
     * @return Сжатое резюме диалога
     */
    suspend fun compressMessages(messages: List<Message>, temperature: Double = 0.3): String {
        if (messages.isEmpty()) {
            return "Пустой диалог"
        }

        // Формируем текст для сжатия
        val dialogText = messages.joinToString("\n\n") { msg ->
            "${msg.role.uppercase()}: ${msg.text}"
        }

        // Промпт для сжатия диалога
        val compressionPrompt = """Ты - система сжатия диалогов. Твоя задача - создать МАКСИМАЛЬНО краткое резюме диалога, сохраняя ключевую информацию.

ВАЖНО:
- Сократи диалог в 5-10 раз
- Сохрани только важные факты, решения и ключевые моменты
- Убери повторы, приветствия, уточнения
- Используй тезисный стиль
- Не добавляй свои комментарии, только выжимка фактов

Диалог для сжатия:
$dialogText

Краткое резюме диалога:"""

        val compressionMessages = listOf(
            Message(role = "user", text = compressionPrompt)
        )

        return runCatching {
            val response = llmClient.sendMessageWithUsage(compressionMessages, temperature)
            println("=== СЖАТИЕ КОНТЕКСТА ===")
            println("Исходно: ${messages.size} сообщений, ${dialogText.length} символов")
            println("Сжато до: ${response.text.length} символов")
            println("Коэффициент сжатия: ${dialogText.length / response.text.length.toDouble()}x")
            response.text
        }.getOrElse { e ->
            println("⚠️ Ошибка сжатия диалога: ${e.message}")
            "Ошибка сжатия: ${e.message}"
        }
    }

    /**
     * Сжимает системный промпт
     *
     * @param systemPrompt Исходный системный промпт
     * @param temperature Температура генерации
     * @return Сжатый системный промпт
     */
    suspend fun compressSystemPrompt(systemPrompt: String, temperature: Double = 0.3): String {
        if (systemPrompt.isBlank()) {
            return systemPrompt
        }

        // Промпт для сжатия системного промпта
        val compressionPrompt = """Ты - система сжатия инструкций. Твоя задача - сократить системный промпт, сохранив ключевые правила и инструкции.

ВАЖНО:
- Сократи в 3-5 раз
- Сохрани главные правила поведения
- Убери примеры, пояснения, повторы
- Используй тезисный стиль
- Не теряй смысл инструкций

Исходный системный промпт:
$systemPrompt

Сжатая версия системного промпта:"""

        val compressionMessages = listOf(
            Message(role = "user", text = compressionPrompt)
        )

        return runCatching {
            val response = llmClient.sendMessageWithUsage(compressionMessages, temperature)
            println("=== СЖАТИЕ СИСТЕМНОГО ПРОМПТА ===")
            println("Исходно: ${systemPrompt.length} символов")
            println("Сжато до: ${response.text.length} символов")
            println("Коэффициент сжатия: ${systemPrompt.length / response.text.length.toDouble()}x")
            response.text
        }.getOrElse { e ->
            println("⚠️ Ошибка сжатия системного промпта: ${e.message}")
            systemPrompt // Возвращаем оригинал при ошибке
        }
    }

    /**
     * Создает новое сжатие или обновляет существующее
     *
     * @param currentMessages Все текущие сообщения сессии
     * @param existingCompression Существующая информация о сжатии (если есть)
     * @param keepLastN Сколько последних сообщений оставить несжатыми (по умолчанию 1 - только последнее)
     * @param temperature Температура генерации
     * @return Обновленная информация о сжатии
     */
    suspend fun createOrUpdateCompression(
        currentMessages: List<Message>,
        existingCompression: CompressionInfo? = null,
        keepLastN: Int = 1,  // По умолчанию оставляем только последнее сообщение
        temperature: Double = 0.3
    ): CompressionInfo? {
        val totalMessages = currentMessages.size

        println("ContextCompressor.createOrUpdateCompression: totalMessages = $totalMessages")

        // Если сообщений меньше 10, не сжимаем
        if (totalMessages < 10) {
            println("ContextCompressor: Сообщений меньше 10, сжатие не создается")
            return null
        }

        // Определяем какие сообщения нужно сжать
        val compressUpTo = totalMessages - keepLastN

        // Если уже есть сжатие
        if (existingCompression != null) {
            val newMessagesCount = compressUpTo - existingCompression.compressedUpToIndex

            // Если новых сообщений меньше 10, не пересжимаем
            if (newMessagesCount < 10) {
                return existingCompression
            }

            // Берем новые сообщения для добавления к резюме
            val newMessages = currentMessages.subList(
                existingCompression.compressedUpToIndex + 1,
                compressUpTo
            )

            // Создаем промпт для обновления резюме
            val updatePrompt = """Предыдущее резюме диалога:
${existingCompression.compressedSummary}

Новые сообщения:
${newMessages.joinToString("\n\n") { "${it.role.uppercase()}: ${it.text}" }}

Обнови резюме, добавив информацию из новых сообщений. Сохраняй краткость."""

            val updatedSummary = compressMessages(
                listOf(Message(role = "user", text = updatePrompt)),
                temperature
            )

            return CompressionInfo(
                compressedUpToIndex = compressUpTo,
                compressedSummary = updatedSummary,
                compressedSystemPrompt = existingCompression.compressedSystemPrompt
            )
        }

        // Первое сжатие - берем все сообщения до compressUpTo
        val messagesToCompress = currentMessages.subList(0, compressUpTo)
        val summary = compressMessages(messagesToCompress, temperature)

        return CompressionInfo(
            compressedUpToIndex = compressUpTo - 1,  // Индекс последнего сжатого сообщения
            compressedSummary = summary,
            compressedSystemPrompt = null
        )
    }
}
