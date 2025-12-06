package kz.shprot.assistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kz.shprot.RAGManager
import kz.shprot.SimpleMcpManager
import kz.shprot.YandexLLMClient
import kz.shprot.models.Message
import kz.shprot.support.*
import kz.shprot.tools.ProjectManager
import org.slf4j.LoggerFactory

/**
 * Командный ассистент - объединяет RAG, MCP и внутренние инструменты.
 * Использует ReAct паттерн для автоматической композиции инструментов.
 *
 * Возможности:
 * - Управление задачами (создание, обновление, поиск, анализ)
 * - Интеграция с GitHub Issues (двусторонняя синхронизация)
 * - Статус проекта и аналитика
 * - Умные рекомендации по приоритетам с учётом контекста проекта (RAG)
 */
class TeamAssistantService(
    private val llmClient: YandexLLMClient,
    private val mcpManager: SimpleMcpManager,
    private val ticketManager: TicketManager,
    private val projectManager: ProjectManager,
    private val ragManager: RAGManager? = null,
    private val maxIterations: Int = 15
) {
    private val logger = LoggerFactory.getLogger(TeamAssistantService::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Внутренние инструменты для работы с задачами
    private val internalTools = InternalToolsRegistry(ticketManager, projectManager, ragManager)

    /**
     * Обрабатывает запрос пользователя через оркестратор.
     * Автоматически выбирает и вызывает нужные инструменты.
     */
    suspend fun processRequest(
        request: AssistantRequest
    ): AssistantResponse {
        logger.info("🤖 TeamAssistant: обработка запроса")
        logger.info("   Проект: ${request.projectId}")
        logger.info("   Запрос: ${request.message}")

        val context = mutableListOf<Message>()
        val toolCalls = mutableListOf<AssistantToolCall>()
        var iteration = 0

        // Формируем system prompt с описанием всех инструментов
        val systemPrompt = buildSystemPrompt(request.projectId)
        context.add(Message("system", systemPrompt))
        context.add(Message("user", request.message))

        while (iteration < maxIterations) {
            iteration++
            logger.info("\n📍 Итерация $iteration/$maxIterations")

            // LLM решает что делать
            val llmResponse = llmClient.sendMessage(
                messages = context,
                temperature = request.temperature,
                useJsonSchema = false
            )

            logger.info("🤖 LLM:\n${llmResponse.take(500)}${if (llmResponse.length > 500) "..." else ""}")

            when {
                // Вызов инструмента
                llmResponse.contains("TOOL_CALL:", ignoreCase = true) -> {
                    val (toolName, params) = parseToolCall(llmResponse)
                    logger.info("🔧 Вызов инструмента: $toolName")
                    logger.info("   Параметры: $params")

                    val toolResult = executeToolCall(toolName, params, request.projectId)

                    logger.info("✅ Результат: ${toolResult.take(300)}...")

                    toolCalls.add(AssistantToolCall(
                        iteration = iteration,
                        toolName = toolName,
                        parameters = params,
                        result = toolResult
                    ))

                    context.add(Message("assistant", llmResponse))
                    context.add(Message("user", "Tool result:\n$toolResult"))
                }

                // Финальный ответ
                llmResponse.contains("FINAL_ANSWER:", ignoreCase = true) -> {
                    val finalAnswer = extractFinalAnswer(llmResponse)
                    logger.info("✅ Финальный ответ получен за $iteration итераций")

                    return AssistantResponse(
                        success = true,
                        answer = finalAnswer,
                        toolCalls = toolCalls,
                        iterations = iteration,
                        projectId = request.projectId
                    )
                }

                // Продолжаем диалог
                else -> {
                    context.add(Message("assistant", llmResponse))
                    context.add(Message("user",
                        "Продолжай. Используй TOOL_CALL для вызова инструмента или FINAL_ANSWER для ответа."))
                }
            }
        }

        logger.warn("⚠️ Превышен лимит итераций")
        return AssistantResponse(
            success = false,
            answer = "Превышен лимит итераций. Частичные результаты:\n" +
                    toolCalls.joinToString("\n") { "- ${it.toolName}: ${it.result.take(100)}..." },
            toolCalls = toolCalls,
            iterations = iteration,
            projectId = request.projectId
        )
    }

    /**
     * Выполняет вызов инструмента (внутреннего или MCP).
     */
    private suspend fun executeToolCall(
        toolName: String,
        params: Map<String, Any>,
        projectId: String
    ): String {
        return runCatching {
            // Сначала проверяем внутренние инструменты
            if (internalTools.hasInternalTool(toolName)) {
                internalTools.executeTool(toolName, params, projectId)
            } else {
                // Иначе пробуем MCP инструмент
                mcpManager.callTool(toolName, params)
            }
        }.getOrElse { e ->
            logger.error("❌ Ошибка вызова $toolName: ${e.message}")
            "ERROR: ${e.message}"
        }
    }

    /**
     * Формирует system prompt с описанием всех доступных инструментов.
     */
    private fun buildSystemPrompt(projectId: String): String {
        // Внутренние инструменты (задачи, проект, аналитика)
        val internalToolsDescription = internalTools.getToolsDescription()

        // MCP инструменты (GitHub, погода, файлы и т.д.)
        val mcpTools = mcpManager.listAllToolsDetailed()
        val mcpToolsDescription = mcpTools.joinToString("\n") {
            "- ${it.name}: ${it.description}\n  Параметры: ${it.parameters}"
        }

        // Контекст проекта для персонализации
        val projectContext = projectManager.getProject(projectId)?.let { project ->
            """
            ТЕКУЩИЙ ПРОЕКТ: ${project.name}
            Тип: ${project.type}
            Путь: ${project.rootPath}
            """.trimIndent()
        } ?: "Проект не выбран"

        return """
Ты - командный ассистент для управления проектами и задачами.

$projectContext

═══════════════════════════════════════════════════════════════
⚡ ПРАВИЛО ВЫБОРА ИНСТРУМЕНТОВ (КРИТИЧЕСКИ ВАЖНО):
═══════════════════════════════════════════════════════════════

🔵 ЛОКАЛЬНЫЕ ЗАДАЧИ (task_*) - используй для:
- "покажи задачи", "список задач", "найди задачи"
- "задачи с приоритетом high/medium/low"
- "создай задачу", "обнови задачу"
- "статус проекта", "аналитика задач"
- Любые запросы про задачи/тикеты БЕЗ упоминания GitHub

🟢 GITHUB (github_*, mcp__github__*) - используй ТОЛЬКО когда:
- Явно упомянут "GitHub", "issue", "PR", "pull request"
- "синхронизируй с GitHub", "создай issue на GitHub"

═══════════════════════════════════════════════════════════════
ВНУТРЕННИЕ ИНСТРУМЕНТЫ (ПРИОРИТЕТНЫЕ):
═══════════════════════════════════════════════════════════════
$internalToolsDescription

═══════════════════════════════════════════════════════════════
MCP ИНСТРУМЕНТЫ (GitHub, внешние сервисы):
═══════════════════════════════════════════════════════════════
$mcpToolsDescription

═══════════════════════════════════════════════════════════════
ФОРМАТ ВЫЗОВА ИНСТРУМЕНТА:
═══════════════════════════════════════════════════════════════
TOOL_CALL: имя_инструмента
PARAMETERS: {"param1": "value1"}

После получения результата - либо вызови следующий инструмент, либо дай финальный ответ:
FINAL_ANSWER: твой ответ (markdown)

═══════════════════════════════════════════════════════════════
ПРИМЕРЫ (ЗАПОМНИ!):
═══════════════════════════════════════════════════════════════

❓ "Покажи задачи с приоритетом high"
✅ TOOL_CALL: task_list
✅ PARAMETERS: {"priority": "HIGH"}

❓ "Покажи задачи с высоким приоритетом и предложи что делать"
✅ TOOL_CALL: task_list
✅ PARAMETERS: {"priority": "HIGH"}
(после получения списка - проанализируй и дай рекомендации в FINAL_ANSWER)

❓ "Создай задачу: Исправить баг, приоритет high"
✅ TOOL_CALL: task_create
✅ PARAMETERS: {"title": "Исправить баг", "priority": "HIGH"}

❓ "Статус проекта"
✅ TOOL_CALL: project_status
✅ PARAMETERS: {}

❓ "Синхронизируй с GitHub" (явно упомянут GitHub!)
✅ TOOL_CALL: github_sync
✅ PARAMETERS: {"owner": "shprotx", "repo": "AiLocalServer"}

═══════════════════════════════════════════════════════════════
⚠️ НЕ ИСПОЛЬЗУЙ github_issues_list для обычных задач!
⚠️ Задачи = task_list, GitHub Issues = mcp__github__list_issues
═══════════════════════════════════════════════════════════════

Обработай запрос пользователя.
        """.trimIndent()
    }

    /**
     * Парсит вызов инструмента из ответа LLM.
     */
    private fun parseToolCall(response: String): Pair<String, Map<String, Any>> {
        val toolNameRegex = """TOOL_CALL:\s*(\w+)""".toRegex(RegexOption.IGNORE_CASE)
        val paramsRegex = """PARAMETERS:\s*(\{[\s\S]*?\})""".toRegex(RegexOption.IGNORE_CASE)

        val toolName = toolNameRegex.find(response)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Не найдено имя инструмента")

        val paramsJson = paramsRegex.find(response)?.groupValues?.get(1) ?: "{}"

        val params = runCatching {
            val jsonElement = json.parseToJsonElement(paramsJson).jsonObject
            jsonElement.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> when {
                        value.isString -> value.content
                        value.booleanOrNull != null -> value.boolean
                        value.intOrNull != null -> value.int
                        value.doubleOrNull != null -> value.double
                        else -> value.content
                    }
                    else -> value.toString()
                }
            }
        }.getOrElse {
            logger.warn("⚠️ Ошибка парсинга параметров: $it")
            emptyMap()
        }

        return toolName to params
    }

    /**
     * Извлекает финальный ответ из ответа LLM.
     */
    private fun extractFinalAnswer(response: String): String {
        val regex = """FINAL_ANSWER:\s*(.+)""".toRegex(
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.find(response)?.groupValues?.get(1)?.trim()
            ?: response.substringAfter("FINAL_ANSWER:", response).trim()
    }
}

/**
 * Запрос к ассистенту.
 */
@Serializable
data class AssistantRequest(
    val message: String,
    val projectId: String,
    val temperature: Double = 0.6,
    val includeRag: Boolean = true
)

/**
 * Ответ ассистента.
 */
@Serializable
data class AssistantResponse(
    val success: Boolean,
    val answer: String,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    val iterations: Int = 0,
    val projectId: String? = null
)

/**
 * Лог вызова инструмента.
 */
@Serializable
data class AssistantToolCall(
    val iteration: Int,
    val toolName: String,
    val parameters: Map<String, @Serializable(with = AnySerializer::class) Any>,
    val result: String
)

/**
 * Сериализатор для Any (простые типы).
 */
object AnySerializer : kotlinx.serialization.KSerializer<Any> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "Any", kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Any) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Any {
        return decoder.decodeString()
    }
}
