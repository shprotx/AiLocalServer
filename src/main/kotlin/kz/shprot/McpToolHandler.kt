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
     */
    suspend fun handleToolCall(
        llmResponse: LLMStructuredResponse,
        conversationHistory: List<Message>,
        temperature: Double = 0.6
    ): LLMStructuredResponse {
        val toolCall = llmResponse.tool_call

        // Если нет tool_call - возвращаем ответ как есть
        if (toolCall == null) {
            return llmResponse
        }

        logger.info("🔧 LLM запросил вызов инструмента: ${toolCall.name}")
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

Теперь сформулируй финальный ответ пользователю на основе этого результата.
            """.trimIndent()
        )

        // Отправляем результат обратно в LLM для финального ответа
        val updatedHistory = conversationHistory + toolResultMessage

        val finalResponse = llmClient.sendMessageWithHistoryAndUsage(
            messages = updatedHistory,
            temperature = temperature
        )

        logger.info("✅ Финальный ответ после вызова инструмента получен")

        return finalResponse.response
    }
}
