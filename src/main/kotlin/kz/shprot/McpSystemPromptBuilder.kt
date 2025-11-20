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
        val hasTelegramTools = tools.any {
            val name = it["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
            name.startsWith("tg_")
        }

        return """
$BASE_PROMPT

## Доступные инструменты

У тебя есть доступ к следующим инструментам:

$toolsDescription

${if (hasTelegramTools) TELEGRAM_INSTRUCTIONS else ""}

## Правила использования инструментов

Если для ответа на вопрос пользователя нужно использовать инструменты, верни в поле "tool_calls" массив объектов, каждый с:
- "name": название инструмента
- "arguments": объект с аргументами (НЕ ВКЛЮЧАЙ параметры со значением null)

ВАЖНО: Можно вызвать НЕСКОЛЬКО инструментов в одном запросе, просто добавь их в массив "tool_calls"!

### Примеры использования инструментов:

**Пример 1: Один инструмент**
{
  "title": "Читаю файл",
  "message": "Читаю содержимое файла moscow-temp.txt",
  "tool_calls": [
    {
      "name": "read_file",
      "arguments": {
        "path": "moscow-temp.txt"
      }
    }
  ]
}

**Пример 2: НЕСКОЛЬКО инструментов одновременно**
{
  "title": "Узнаю температуру и сохраняю",
  "message": "Узнаю текущую температуру в Москве и сохраню в файл",
  "tool_calls": [
    {
      "name": "get_current_temperature",
      "arguments": {
        "latitude": 55.7558,
        "longitude": 37.6173
      }
    },
    {
      "name": "write_file",
      "arguments": {
        "path": "moscow-temp.txt",
        "content": "Температура будет записана после получения"
      }
    }
  ]
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

    private const val TELEGRAM_INSTRUCTIONS = """
## Специальные инструкции для Telegram инструментов

**ВАЖНО:** Для работы с Telegram используй ДВУХШАГОВЫЙ подход:

### Шаг 1: Получить список диалогов
Сначала вызови `tg_dialogs` чтобы получить список всех чатов/каналов.
Результат содержит массив диалогов с полями:
- `id` - уникальный идентификатор диалога
- `name` - название чата/канала
- `type` - тип (channel, chat, user)
- `unread_count` - количество непрочитанных

### Шаг 2: Использовать ID диалога
После получения списка диалогов:
1. Найди нужный диалог по названию в результатах
2. Используй его `id` для вызова `tg_dialog`

**Пример правильного workflow:**

Вопрос: "Покажи сообщения из канала Mobile Dev Jobs"

Шаг 1 - Вызываем tg_dialogs:
{
  "tool_calls": [
    {
      "name": "tg_dialogs",
      "arguments": {}
    }
  ]
}

Получаем результат с ID канала, например: `{"id": "1234567890", "name": "Mobile Dev Jobs", "type": "channel"}`

Шаг 2 - Вызываем tg_dialog с ID:
{
  "tool_calls": [
    {
      "name": "tg_dialog",
      "arguments": {
        "id": "1234567890"
      }
    }
  ]
}

**НЕ ИСПОЛЬЗУЙ название канала напрямую в tg_dialog** - это вызовет ошибку!
    """
}
