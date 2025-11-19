package kz.shprot

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kz.shprot.models.LLMStructuredResponse
import kz.shprot.models.Message
import org.slf4j.LoggerFactory

/**
 * Обработчик вызовов MCP инструментов
 */
class McpToolHandler(
    private val mcpManager: SimpleMcpManager,
    private val llmClient: YandexLLMClient
) {
    private val logger = LoggerFactory.getLogger(McpToolHandler::class.java)

    /**
     * Обрабатывает tool_call если он есть в ответе LLM
     * Возвращает финальный ответ после вызова инструмента
     * Поддерживает цепочку вызовов (LLM может вызвать несколько инструментов подряд)
     */
    suspend fun handleToolCall(
        llmResponse: LLMStructuredResponse,
        conversationHistory: List<Message>,
        temperature: Double = 0.6,
        maxIterations: Int = 5
    ): LLMStructuredResponse {
        var currentResponse = llmResponse
        var currentHistory = conversationHistory
        var iteration = 0

        // Цикл обработки tool_call - продолжаем пока LLM запрашивает инструменты
        while (currentResponse.tool_call != null && iteration < maxIterations) {
            iteration++
            val toolCall = currentResponse.tool_call!!

            logger.info("🔧 [$iteration/$maxIterations] LLM запросил вызов инструмента: ${toolCall.name}")
            logger.info("   Аргументы: ${toolCall.arguments}")

            // Конвертируем аргументы из JsonElement в Map<String, Any>
            // Фильтруем null значения, чтобы не отправлять их в MCP серверы
            val arguments = toolCall.arguments
                .filterNot { (_, value) -> value is JsonNull }
                .mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> {
                            when {
                                value.isString -> value.content
                                value.booleanOrNull != null -> value.boolean
                                // ВАЖНО: Сначала проверяем longOrNull, потом doubleOrNull
                                // Иначе целые числа будут преобразованы в Double (7 -> 7.0)
                                value.longOrNull != null -> value.long
                                value.doubleOrNull != null -> value.double
                                else -> value.content
                            }
                        }
                        else -> value.toString()
                    }
                }

            // Вызываем MCP инструмент
            val toolResult = try {
                mcpManager.callTool(toolCall.name, arguments)
            } catch (e: Exception) {
                logger.error("❌ Ошибка вызова инструмента: ${e.message}", e)
                "Ошибка: ${e.message}"
            }

            logger.info("📦 Результат инструмента: $toolResult")

            // Создаем новое сообщение с результатом инструмента
            val toolResultMessage = Message(
                role = "user",
                text = """
Результат вызова инструмента "${toolCall.name}":
```json
$toolResult
```

${if (iteration < maxIterations) "Если нужно вызвать еще инструменты - сделай это. Иначе сформулируй финальный ответ пользователю." else "Сформулируй финальный ответ пользователю на основе всех результатов."}
                """.trimIndent()
            )

            // Обновляем историю
            currentHistory = currentHistory + toolResultMessage

            // Отправляем результат обратно в LLM
            val nextResponse = llmClient.sendMessageWithHistoryAndUsage(
                messages = currentHistory,
                temperature = temperature
            )

            currentResponse = nextResponse.response
        }

        if (iteration >= maxIterations && currentResponse.tool_call != null) {
            logger.warn("⚠️ Достигнут лимит итераций ($maxIterations), но LLM все еще запрашивает инструменты")
        }

        logger.info("✅ Финальный ответ получен после $iteration вызовов инструментов")

        return currentResponse
    }
}
