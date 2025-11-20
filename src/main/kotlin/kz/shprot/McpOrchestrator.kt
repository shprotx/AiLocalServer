package kz.shprot

import kotlinx.serialization.json.*
import kz.shprot.models.Message
import org.slf4j.LoggerFactory

/**
 * Оркестратор для автоматической композиции MCP инструментов
 * Использует ReAct паттерн (Reasoning + Acting):
 * - LLM сама решает какие инструменты вызывать
 * - Длина цепочки не фиксирована
 * - Пример: "Найди документ X → Суммаризируй → Сохрани в файл Y"
 */
class McpOrchestrator(
    private val mcpManager: SimpleMcpManager,
    private val llmClient: YandexLLMClient,
    private val maxIterations: Int = 15
) {
    private val logger = LoggerFactory.getLogger(McpOrchestrator::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Выполняет задачу пользователя через цепочку MCP инструментов
     */
    suspend fun executeTask(userRequest: String, temperature: Double = 0.6): OrchestrationResult {
        logger.info("🎯 Начинаем выполнение задачи через MCP Orchestrator")
        logger.info("   Запрос: $userRequest")

        val context = mutableListOf<Message>()
        val toolCalls = mutableListOf<ToolCallLog>()
        var iteration = 0

        // Формируем system prompt с описанием доступных инструментов
        val systemPrompt = buildSystemPrompt()
        context.add(Message("system", systemPrompt))
        context.add(Message("user", userRequest))

        while (iteration < maxIterations) {
            iteration++
            logger.info("\n📍 Итерация $iteration/$maxIterations")

            // LLM решает что делать дальше
            val llmResponse = llmClient.sendMessage(
                messages = context,
                temperature = temperature,
                useJsonSchema = false
            )

            logger.info("🤖 LLM ответ:\n${llmResponse.take(500)}${if (llmResponse.length > 500) "..." else ""}")

            // Парсим ответ
            when {
                // Вызов инструмента
                llmResponse.contains("TOOL_CALL:", ignoreCase = true) -> {
                    val (toolName, params) = parseToolCall(llmResponse)

                    logger.info("🔧 Вызов MCP инструмента: $toolName")
                    logger.info("   Параметры: $params")

                    // Выполняем вызов
                    val toolResult = runCatching {
                        mcpManager.callTool(toolName, params)
                    }.getOrElse { e ->
                        logger.error("❌ Ошибка вызова инструмента $toolName: ${e.message}")
                        "ERROR: ${e.message}"
                    }

                    logger.info("✅ Результат: ${toolResult.take(300)}${if (toolResult.length > 300) "..." else ""}")

                    // Сохраняем лог вызова
                    toolCalls.add(ToolCallLog(
                        iteration = iteration,
                        toolName = toolName,
                        parameters = params,
                        result = toolResult
                    ))

                    // Добавляем в контекст
                    context.add(Message("assistant", llmResponse))
                    context.add(Message("user", "Tool result:\n$toolResult"))
                }

                // Финальный ответ
                llmResponse.contains("FINAL_ANSWER:", ignoreCase = true) -> {
                    val finalAnswer = extractFinalAnswer(llmResponse)
                    logger.info("✅ Финальный ответ получен")
                    logger.info("   Всего итераций: $iteration")
                    logger.info("   Всего вызовов MCP: ${toolCalls.size}")

                    return OrchestrationResult(
                        success = true,
                        finalAnswer = finalAnswer,
                        toolCalls = toolCalls,
                        iterations = iteration
                    )
                }

                // Продолжаем диалог
                else -> {
                    context.add(Message("assistant", llmResponse))
                    context.add(Message("user", "Продолжай. Если нужно вызвать инструмент - используй формат TOOL_CALL. Если задача выполнена - используй формат FINAL_ANSWER."))
                }
            }
        }

        logger.warn("⚠️ Превышен лимит итераций ($maxIterations)")
        return OrchestrationResult(
            success = false,
            finalAnswer = "Превышен лимит итераций. Задача не завершена.",
            toolCalls = toolCalls,
            iterations = iteration
        )
    }

    /**
     * Формирует system prompt с описанием доступных инструментов
     */
    private fun buildSystemPrompt(): String {
        val availableTools = mcpManager.listAllToolsDetailed()

        return """
Ты - автоматический агент-оркестратор, который выполняет задачи пользователя через композицию MCP инструментов.

ДОСТУПНЫЕ ИНСТРУМЕНТЫ:
${availableTools.joinToString("\n") { "- ${it.name}: ${it.description}\n  Параметры: ${it.parameters}" }}

ФОРМАТ РАБОТЫ:
1. Проанализируй запрос пользователя
2. Если нужно вызвать инструмент, используй формат:
   TOOL_CALL: имя_инструмента
   PARAMETERS: {"param1": "value1", "param2": "value2"}

3. Получи результат и решай дальше
4. Когда задача выполнена, используй формат:
   FINAL_ANSWER: твой итоговый ответ пользователю

ВАЖНО:
- ОБЯЗАТЕЛЬНО выполни ВСЕ части задачи пользователя, не пропускай ни одну!
- Если задача содержит несколько действий (например "получи И сохрани") - выполни их ВСЕ
- Ты можешь вызывать инструменты последовательно (цепочка не ограничена)
- Каждый следующий вызов может использовать результаты предыдущих
- Если инструмент вернул ошибку - попробуй другой подход
- FINAL_ANSWER используй ТОЛЬКО когда ВСЕ части задачи выполнены
- Перед FINAL_ANSWER проверь: выполнил ли ты все что просил пользователь?

ПРИМЕРЫ:

Запрос: "Найди документацию по Kotlin на kotlinlang.org и сохрани в файл kotlin-docs.md"
Шаг 1:
TOOL_CALL: fetch
PARAMETERS: {"url": "https://kotlinlang.org/docs/"}

Шаг 2 (после получения контента):
TOOL_CALL: write_file
PARAMETERS: {"path": "kotlin-docs.md", "content": "<полученный контент>"}

Шаг 3:
FINAL_ANSWER: Документация Kotlin успешно сохранена в файл kotlin-docs.md

---

Запрос: "Получи прогноз погоды для Москвы и сохрани в файл weather-moscow.txt"
Шаг 1:
TOOL_CALL: get_forecast
PARAMETERS: {"city": "Москва"}

Шаг 2 (ОБЯЗАТЕЛЬНО! Задача содержит "И сохрани"):
TOOL_CALL: write_file
PARAMETERS: {"path": "weather-moscow.txt", "content": "<прогноз>"}

Шаг 3:
FINAL_ANSWER: Прогноз погоды для Москвы сохранен в файл weather-moscow.txt

---

Запрос: "Узнай температуру в Москве (координаты 55.7558, 37.6173) и сохрани результат в файл moscow-temp.txt"
Шаг 1:
TOOL_CALL: get_current_temperature
PARAMETERS: {"latitude": 55.7558, "longitude": 37.6173}

Шаг 2 (ОБЯЗАТЕЛЬНО! Пользователь просил "И сохрани результат"):
TOOL_CALL: write_file
PARAMETERS: {"path": "moscow-temp.txt", "content": "Текущая температура в Москве: -1.0°C"}

Шаг 3:
FINAL_ANSWER: Температура в Москве (-1.0°C) успешно сохранена в файл moscow-temp.txt

---

Теперь выполни запрос пользователя, следуя этому формату.
ПОМНИ: Если запрос содержит "И" (получи И сохрани, узнай И запиши) - это ДВЕ отдельные задачи!
        """.trimIndent()
    }

    /**
     * Парсит вызов инструмента из ответа LLM
     */
    private fun parseToolCall(response: String): Pair<String, Map<String, Any>> {
        val toolNameRegex = """TOOL_CALL:\s*(\w+)""".toRegex(RegexOption.IGNORE_CASE)
        val paramsRegex = """PARAMETERS:\s*(\{[\s\S]*?\})""".toRegex(RegexOption.IGNORE_CASE)

        val toolName = toolNameRegex.find(response)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Не удалось найти имя инструмента в ответе")

        val paramsJson = paramsRegex.find(response)?.groupValues?.get(1) ?: "{}"

        val params = runCatching {
            val jsonElement = json.parseToJsonElement(paramsJson).jsonObject
            jsonElement.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull ?: value.toString()
                    else -> value.toString()
                }
            }
        }.getOrElse {
            logger.warn("⚠️ Не удалось распарсить параметры, используем пустой объект: $it")
            emptyMap()
        }

        return toolName to params
    }

    /**
     * Извлекает финальный ответ из ответа LLM
     */
    private fun extractFinalAnswer(response: String): String {
        val finalAnswerRegex = """FINAL_ANSWER:\s*(.+)""".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return finalAnswerRegex.find(response)?.groupValues?.get(1)?.trim()
            ?: response.substringAfter("FINAL_ANSWER:", response).trim()
    }
}

/**
 * Результат выполнения задачи через оркестратор
 */
data class OrchestrationResult(
    val success: Boolean,
    val finalAnswer: String,
    val toolCalls: List<ToolCallLog>,
    val iterations: Int
)

/**
 * Лог вызова инструмента
 */
data class ToolCallLog(
    val iteration: Int,
    val toolName: String,
    val parameters: Map<String, Any>,
    val result: String
)
