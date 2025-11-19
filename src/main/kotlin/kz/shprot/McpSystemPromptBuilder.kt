package kz.shprot

import kotlinx.serialization.json.*

/**
 * Помощник для построения system prompt с описанием доступных MCP инструментов
 */
object McpSystemPromptBuilder {

    /**
     * Создает system prompt с описанием MCP инструментов
     */
    fun buildSystemPrompt(mcpManager: SimpleMcpManager): String {
        val tools = mcpManager.getToolsForFunctionCalling()

        if (tools.isEmpty()) {
            return BASE_PROMPT
        }

        val toolsDescription = buildToolsDescription(tools)

        return """
$BASE_PROMPT

## Доступные инструменты

У тебя есть доступ к следующим инструментам:

$toolsDescription

Если для ответа на вопрос пользователя нужно использовать инструмент, верни в поле "tool_call" объект с:
- "name": название инструмента
- "arguments": объект с аргументами

Пример:
{
  "title": "Получаю температуру",
  "message": "Сейчас узнаю текущую температуру в Москве",
  "tool_call": {
    "name": "get_current_temperature",
    "arguments": {
      "latitude": 55.7558,
      "longitude": 37.6173
    }
  }
}

После получения результата от инструмента, ты получишь новое сообщение с результатом и сможешь сформулировать финальный ответ.
        """.trimIndent()
    }

    private fun buildToolsDescription(tools: List<JsonObject>): String {
        return tools.joinToString("\n\n") { tool ->
            val function = tool["function"]?.jsonObject ?: return@joinToString ""
            val name = function["name"]?.jsonPrimitive?.content ?: "unknown"
            val description = function["description"]?.jsonPrimitive?.content ?: "No description"
            val parameters = function["parameters"]?.jsonObject

            buildString {
                appendLine("### $name")
                appendLine(description)

                if (parameters != null) {
                    appendLine()
                    appendLine("**Параметры:**")

                    val properties = parameters["properties"]?.jsonObject
                    val required = parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                    properties?.forEach { (paramName, paramSchema) ->
                        val paramObj = paramSchema.jsonObject
                        val paramType = paramObj["type"]?.jsonPrimitive?.content ?: "any"
                        val paramDesc = paramObj["description"]?.jsonPrimitive?.content ?: ""
                        val isRequired = if (paramName in required) " (обязательный)" else ""

                        appendLine("- `$paramName` ($paramType)$isRequired: $paramDesc")
                    }
                }
            }
        }
    }

    private const val BASE_PROMPT = """
Ты - полезный ассистент. Отвечай на вопросы пользователя кратко и по делу.

Твои ответы всегда должны быть в JSON формате:
{
  "title": "Краткий заголовок (2-5 слов)",
  "message": "Полный текст ответа"
}
    """
}
