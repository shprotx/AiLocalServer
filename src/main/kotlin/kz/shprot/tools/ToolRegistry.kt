package kz.shprot.tools

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр инструментов.
 * Управляет регистрацией, поиском и выполнением инструментов.
 * Thread-safe благодаря использованию ConcurrentHashMap.
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool>()
    private val toolCategories = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Регистрирует инструмент в реестре.
     * @param tool Инструмент для регистрации
     * @param category Опциональная категория (например, "file", "git", "project")
     */
    fun register(tool: Tool, category: String? = null) {
        tools[tool.name] = tool
        category?.let {
            toolCategories.getOrPut(it) { mutableSetOf() }.add(tool.name)
        }
        println("[ToolRegistry] Registered tool: ${tool.name}" + (category?.let { " (category: $it)" } ?: ""))
    }

    /**
     * Регистрирует несколько инструментов сразу.
     */
    fun registerAll(vararg toolsToRegister: Pair<Tool, String?>) {
        toolsToRegister.forEach { (tool, category) ->
            register(tool, category)
        }
    }

    /**
     * Удаляет инструмент из реестра.
     */
    fun unregister(toolName: String) {
        tools.remove(toolName)
        toolCategories.values.forEach { it.remove(toolName) }
        println("[ToolRegistry] Unregistered tool: $toolName")
    }

    /**
     * Получает инструмент по имени.
     */
    fun get(name: String): Tool? = tools[name]

    /**
     * Проверяет, существует ли инструмент.
     */
    fun exists(name: String): Boolean = tools.containsKey(name)

    /**
     * Возвращает все зарегистрированные инструменты.
     */
    fun getAll(): List<Tool> = tools.values.toList()

    /**
     * Возвращает инструменты по категории.
     */
    fun getByCategory(category: String): List<Tool> {
        return toolCategories[category]?.mapNotNull { tools[it] } ?: emptyList()
    }

    /**
     * Возвращает все категории.
     */
    fun getCategories(): Set<String> = toolCategories.keys.toSet()

    /**
     * Возвращает количество зарегистрированных инструментов.
     */
    fun count(): Int = tools.size

    /**
     * Выполняет инструмент по имени.
     * @param toolName Имя инструмента
     * @param parameters Параметры в JSON формате
     * @param context Контекст выполнения
     * @return Результат выполнения или ошибка если инструмент не найден
     */
    suspend fun execute(
        toolName: String,
        parameters: JsonObject,
        context: ToolExecutionContext
    ): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult.Error(
                message = "Tool '$toolName' not found",
                errorCode = "TOOL_NOT_FOUND",
                recoverable = false
            )

        return runCatching {
            tool.execute(parameters, context)
        }.getOrElse { e ->
            ToolResult.Error(
                message = "Error executing tool '$toolName': ${e.message}",
                errorCode = "EXECUTION_ERROR",
                recoverable = true
            )
        }
    }

    /**
     * Генерирует схему всех инструментов для LLM Function Calling.
     */
    fun toFunctionCallingSchema(): List<JsonObject> {
        return tools.values.map { it.toFunctionCallingFormat() }
    }

    /**
     * Генерирует текстовое описание всех инструментов для system prompt.
     */
    fun toSystemPromptDescription(): String {
        if (tools.isEmpty()) return "Нет доступных инструментов."

        val builder = StringBuilder()
        builder.appendLine("# Доступные инструменты\n")

        // Группируем по категориям
        val categorized = mutableMapOf<String, MutableList<Tool>>()
        val uncategorized = mutableListOf<Tool>()

        tools.values.forEach { tool ->
            val category = toolCategories.entries.find { tool.name in it.value }?.key
            if (category != null) {
                categorized.getOrPut(category) { mutableListOf() }.add(tool)
            } else {
                uncategorized.add(tool)
            }
        }

        // Выводим по категориям
        categorized.forEach { (category, categoryTools) ->
            builder.appendLine("## ${category.replaceFirstChar { it.uppercase() }}")
            categoryTools.forEach { tool ->
                builder.appendLine(formatToolDescription(tool))
            }
            builder.appendLine()
        }

        // Выводим некатегоризированные
        if (uncategorized.isNotEmpty()) {
            builder.appendLine("## Прочие")
            uncategorized.forEach { tool ->
                builder.appendLine(formatToolDescription(tool))
            }
        }

        return builder.toString()
    }

    private fun formatToolDescription(tool: Tool): String {
        val params = tool.parametersSchema.properties.entries.joinToString(", ") { (name, param) ->
            val required = if (name in tool.parametersSchema.required) "*" else ""
            "$name$required: ${param.type}"
        }
        return """
            |### ${tool.name}
            |${tool.description}
            |Параметры: $params
        """.trimMargin()
    }

    /**
     * Ищет инструменты по ключевому слову в имени или описании.
     */
    fun search(keyword: String): List<Tool> {
        val lowerKeyword = keyword.lowercase()
        return tools.values.filter { tool ->
            tool.name.lowercase().contains(lowerKeyword) ||
                    tool.description.lowercase().contains(lowerKeyword)
        }
    }

    companion object {
        /**
         * Создаёт реестр с предустановленным набором инструментов.
         */
        fun withDefaults(): ToolRegistry {
            return ToolRegistry().apply {
                // Здесь будут регистрироваться стандартные инструменты
                // после их создания
            }
        }
    }
}
