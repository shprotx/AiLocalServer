package kz.shprot.tools

import kotlinx.serialization.json.*

/**
 * Исполнитель инструментов.
 * Обрабатывает вызовы инструментов от LLM и возвращает результаты.
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val projectManager: ProjectManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Выполняет один инструмент.
     */
    suspend fun execute(
        toolName: String,
        arguments: JsonObject
    ): ToolResult {
        val context = projectManager.createExecutionContext()

        println("[ToolExecutor] Executing tool: $toolName")
        println("[ToolExecutor] Arguments: $arguments")
        println("[ToolExecutor] Context: project=${context.projectName}, root=${context.projectRoot}")

        val result = toolRegistry.execute(toolName, arguments, context)

        when (result) {
            is ToolResult.Success -> {
                println("[ToolExecutor] Success: ${result.output.take(200)}...")
            }
            is ToolResult.Error -> {
                println("[ToolExecutor] Error: ${result.message}")
            }
        }

        return result
    }

    /**
     * Выполняет несколько инструментов последовательно.
     * Каждый следующий инструмент видит результаты предыдущих.
     */
    suspend fun executeSequence(
        toolCalls: List<ToolCall>
    ): List<Pair<ToolCall, ToolResult>> {
        val results = mutableListOf<Pair<ToolCall, ToolResult>>()

        for (call in toolCalls) {
            val arguments = call.arguments.let { args ->
                if (args is JsonObject) args
                else buildJsonObject { }
            }

            val result = execute(call.name, arguments)
            results.add(call to result)

            // Если ошибка и она не recoverable - прекращаем
            if (result is ToolResult.Error && !result.recoverable) {
                break
            }
        }

        return results
    }

    /**
     * Форматирует результат выполнения для передачи обратно в LLM.
     */
    fun formatResultForLLM(toolName: String, result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> """
                |Tool: $toolName
                |Status: SUCCESS
                |Output:
                |${result.output}
            """.trimMargin()

            is ToolResult.Error -> """
                |Tool: $toolName
                |Status: ERROR
                |Error: ${result.message}
                |${if (result.recoverable) "This error is recoverable. You can try again with different parameters." else "This error is not recoverable."}
            """.trimMargin()
        }
    }

    /**
     * Форматирует результаты нескольких инструментов.
     */
    fun formatResultsForLLM(results: List<Pair<ToolCall, ToolResult>>): String {
        if (results.isEmpty()) return "No tools were executed."

        return results.joinToString("\n\n---\n\n") { (call, result) ->
            formatResultForLLM(call.name, result)
        }
    }

    /**
     * Парсит tool_calls из ответа LLM.
     */
    fun parseToolCalls(toolCallsJson: List<JsonElement>?): List<ToolCall> {
        if (toolCallsJson.isNullOrEmpty()) return emptyList()

        return toolCallsJson.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val arguments = obj["arguments"]?.jsonObject ?: buildJsonObject { }

                ToolCall(name, arguments)
            }.getOrNull()
        }
    }

    /**
     * Представление вызова инструмента.
     */
    data class ToolCall(
        val name: String,
        val arguments: JsonObject
    )

    /**
     * Генерирует схему инструментов для system prompt.
     */
    fun generateToolsPrompt(): String {
        val tools = toolRegistry.getAll()
        if (tools.isEmpty()) return ""

        return buildString {
            appendLine("# Available Tools")
            appendLine()
            appendLine("You have access to the following tools. To use a tool, respond with a JSON object containing 'tool_calls' array.")
            appendLine()

            tools.forEach { tool ->
                appendLine("## ${tool.name}")
                appendLine(tool.description.trim())
                appendLine()
                appendLine("Parameters:")

                tool.parametersSchema.properties.forEach { (name, param) ->
                    val required = if (name in tool.parametersSchema.required) " (required)" else ""
                    appendLine("- `$name` (${param.type})$required: ${param.description}")
                }
                appendLine()
            }

            appendLine("## Usage Example")
            appendLine("""
                |To read a file:
                |```json
                |{
                |  "tool_calls": [
                |    {"name": "read_file", "arguments": {"path": "src/main/kotlin/Example.kt"}}
                |  ]
                |}
                |```
                |
                |To search and then edit:
                |```json
                |{
                |  "tool_calls": [
                |    {"name": "grep", "arguments": {"pattern": "TODO", "glob": "**/*.kt"}},
                |    {"name": "edit_file", "arguments": {"path": "file.kt", "old_string": "TODO", "new_string": "DONE"}}
                |  ]
                |}
                |```
            """.trimMargin())
        }
    }
}
