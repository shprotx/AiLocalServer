package kz.shprot

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kz.shprot.models.LLMStructuredResponse
import kz.shprot.models.Message
import org.slf4j.LoggerFactory

/**
 * Результат обработки tool calls с информацией об использованных инструментах
 */
data class ToolCallResult(
    val response: LLMStructuredResponse,
    val usedTools: List<String> // Список имен использованных инструментов
)

/**
 * Обработчик вызовов MCP инструментов
 */
class McpToolHandler(
    private val mcpManager: SimpleMcpManager,
    private val llmClient: YandexLLMClient
) {
    private val logger = LoggerFactory.getLogger(McpToolHandler::class.java)

    /**
     * Обрабатывает tool_calls если они есть в ответе LLM
     * Возвращает финальный ответ после вызова инструментов + список использованных инструментов
     * Поддерживает цепочку вызовов (LLM может вызвать несколько инструментов подряд)
     */
    suspend fun handleToolCalls(
        llmResponse: LLMStructuredResponse,
        conversationHistory: List<Message>,
        temperature: Double = 0.6,
        maxIterations: Int = 5
    ): ToolCallResult {
        var currentResponse = llmResponse
        var currentHistory = conversationHistory
        var iteration = 0
        val usedTools = mutableListOf<String>() // Собираем все использованные инструменты

        // Цикл обработки tool_calls - продолжаем пока LLM запрашивает инструменты
        while (!currentResponse.tool_calls.isNullOrEmpty() && iteration < maxIterations) {
            iteration++
            val toolCalls = currentResponse.tool_calls!!

            logger.info("🔧 [Итерация $iteration/$maxIterations] LLM запросил ${toolCalls.size} инструмент(ов)")

            // Обрабатываем все вызовы инструментов из текущего запроса
            val toolResults = mutableListOf<String>()

            toolCalls.forEachIndexed { index, toolCall ->
                logger.info("   [${index + 1}/${toolCalls.size}] Вызов: ${toolCall.name}")
                logger.info("   Аргументы: ${toolCall.arguments}")

                // Добавляем в список использованных инструментов
                usedTools.add(toolCall.name)

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

                logger.info("📦 Результат инструмента ${toolCall.name}: $toolResult")

                toolResults.add("""
Результат вызова инструмента "${toolCall.name}":
```json
$toolResult
```
                """.trimIndent())
            }

            // Создаем новое сообщение с результатами всех инструментов
            val toolResultMessage = Message(
                role = "user",
                text = """
${toolResults.joinToString("\n\n")}

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

        if (iteration >= maxIterations && !currentResponse.tool_calls.isNullOrEmpty()) {
            logger.warn("⚠️ Достигнут лимит итераций ($maxIterations), но LLM все еще запрашивает инструменты")
        }

        val totalToolCalls = usedTools.size
        logger.info("✅ Финальный ответ получен после $iteration итераций и $totalToolCalls вызовов инструментов")
        if (usedTools.isNotEmpty()) {
            logger.info("📋 Использованные инструменты: ${usedTools.joinToString(", ")}")
        }

        return ToolCallResult(
            response = currentResponse,
            usedTools = usedTools
        )
    }
}
