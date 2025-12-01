package kz.shprot

import kotlinx.serialization.json.*
import kz.shprot.tools.ToolRegistry
import kz.shprot.tools.ProjectManager

/**
 * Помощник для построения system prompt с описанием доступных MCP инструментов
 */
object McpSystemPromptBuilder {

    /**
     * Создает system prompt с описанием MCP инструментов и Tool Registry инструментов
     */
    fun buildSystemPrompt(
        mcpManager: SimpleMcpManager,
        toolRegistry: ToolRegistry? = null,
        projectManager: ProjectManager? = null
    ): String {
        val mcpTools = mcpManager.getToolsForFunctionCalling()
        val registryToolsPrompt = if (toolRegistry != null && projectManager?.getCurrentProject() != null) {
            buildToolRegistryDescription(toolRegistry, projectManager)
        } else {
            ""
        }

        return buildCombinedPrompt(mcpTools, registryToolsPrompt, projectManager)
    }

    /**
     * Создает system prompt только с описанием MCP инструментов (для обратной совместимости)
     */
    fun buildSystemPromptMcpOnly(mcpManager: SimpleMcpManager): String {
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

### ВАЖНО: Последовательные vs одновременные вызовы

**Если инструменты ЗАВИСЯТ друг от друга** (результат одного нужен для другого):
- Вызывай их ПО ОДНОМУ
- Получишь результат первого → используешь его для вызова второго
- Пример: сначала узнать температуру, потом записать её в файл

**Если инструменты НЕЗАВИСИМЫ**:
- Можешь вызвать несколько одновременно в одном массиве
- Пример: прочитать два разных файла

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

**Пример 2: Несколько НЕЗАВИСИМЫХ инструментов**
{
  "title": "Читаю файлы",
  "message": "Читаю содержимое обоих файлов",
  "tool_calls": [
    {
      "name": "read_file",
      "arguments": {
        "path": "file1.txt"
      }
    },
    {
      "name": "read_file",
      "arguments": {
        "path": "file2.txt"
      }
    }
  ]
}

**Пример 3: ЗАВИСИМЫЕ инструменты (вызывай по одному!)**
Запрос: "Узнай температуру в Москве и сохрани в файл"

Шаг 1 - Сначала узнаём температуру:
{
  "title": "Узнаю температуру",
  "message": "Запрашиваю текущую температуру в Москве",
  "tool_calls": [
    {
      "name": "get_current_temperature",
      "arguments": {
        "latitude": 55.7558,
        "longitude": 37.6173
      }
    }
  ]
}

→ Получаешь результат: {"temperature": -2.0}

Шаг 2 - Теперь сохраняешь РЕАЛЬНУЮ температуру:
{
  "title": "Сохраняю в файл",
  "message": "Сохраняю температуру в файл",
  "tool_calls": [
    {
      "name": "write_file",
      "arguments": {
        "path": "moscow-temp.txt",
        "content": "Температура в Москве: -2.0°C"
      }
    }
  ]
}

После получения результата от инструмента, ты получишь новое сообщение с результатом и сможешь вызвать следующий инструмент или сформулировать финальный ответ.
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

    /**
     * Генерирует описание Tool Registry инструментов
     */
    private fun buildToolRegistryDescription(toolRegistry: ToolRegistry, projectManager: ProjectManager): String {
        val tools = toolRegistry.getAll()
        if (tools.isEmpty()) return ""

        val currentProject = projectManager.getCurrentProject()
        val projectInfo = if (currentProject != null) {
            """
## Текущий проект

**Название:** ${currentProject.name}
**Путь:** ${currentProject.rootPath}
**Тип:** ${currentProject.type.name}
${if (projectManager.getGitBranch() != null) "**Git ветка:** ${projectManager.getGitBranch()}" else ""}

Все пути к файлам относительны корня проекта (${currentProject.rootPath}).
            """.trimIndent()
        } else ""

        return buildString {
            appendLine("## Инструменты для работы с проектом")
            appendLine()
            if (projectInfo.isNotBlank()) {
                appendLine(projectInfo)
                appendLine()
            }

            // Группируем по категориям
            val categories = toolRegistry.getCategories()

            if (categories.isNotEmpty()) {
                for (category in categories) {
                    val categoryTools = toolRegistry.getByCategory(category)
                    if (categoryTools.isEmpty()) continue

                    appendLine("### $category")
                    appendLine()

                    for (tool in categoryTools) {
                        appendLine("#### ${tool.name}")
                        appendLine(tool.description.trim())
                        appendLine()
                        appendLine("**Параметры:**")

                        tool.parametersSchema.properties.forEach { (paramName, param) ->
                            val required = if (paramName in tool.parametersSchema.required) " (обязательный)" else ""
                            appendLine("- `$paramName` (${param.type})$required: ${param.description}")
                        }
                        appendLine()
                    }
                }
            } else {
                // Если категорий нет - просто перечисляем все инструменты
                for (tool in tools) {
                    appendLine("### ${tool.name}")
                    appendLine(tool.description.trim())
                    appendLine()
                    appendLine("**Параметры:**")

                    tool.parametersSchema.properties.forEach { (paramName, param) ->
                        val required = if (paramName in tool.parametersSchema.required) " (обязательный)" else ""
                        appendLine("- `$paramName` (${param.type})$required: ${param.description}")
                    }
                    appendLine()
                }
            }
        }
    }

    /**
     * Создает комбинированный prompt с MCP и Tool Registry инструментами
     */
    private fun buildCombinedPrompt(
        mcpTools: List<JsonObject>,
        registryToolsPrompt: String,
        projectManager: ProjectManager?
    ): String {
        val mcpToolsDescription = if (mcpTools.isNotEmpty()) {
            "## MCP Инструменты\n\n" + buildToolsDescription(mcpTools)
        } else ""

        val hasTelegramTools = mcpTools.any {
            val name = it["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
            name.startsWith("tg_")
        }

        val hasProjectTools = registryToolsPrompt.isNotBlank()

        // Если нет ни MCP, ни Registry инструментов - возвращаем базовый промпт
        if (mcpTools.isEmpty() && !hasProjectTools) {
            return BASE_PROMPT
        }

        return """
$BASE_PROMPT

${if (hasProjectTools) registryToolsPrompt else ""}

${if (mcpToolsDescription.isNotBlank()) mcpToolsDescription else ""}

${if (hasTelegramTools) TELEGRAM_INSTRUCTIONS else ""}

## Правила использования инструментов

**ВАЖНО:** Ты РЕАЛЬНО можешь изменять файлы в проекте! Инструменты edit_file, write_file, grep - работают!

Если для ответа на вопрос пользователя нужно использовать инструменты, верни в поле "tool_calls" массив объектов:
- "name": название инструмента
- "arguments": объект с аргументами

### КРИТИЧЕСКИ ВАЖНО: Алгоритм работы с файлами

**НИКОГДА не угадывай путь к файлу!** Всегда сначала найди его:

1. **Найди файл** через `find_file` (по имени) или `grep` (по содержимому)
2. **Прочитай файл** через `read_file` чтобы увидеть ТОЧНЫЙ текст
3. **Отредактируй** через `edit_file` используя ТОЧНУЮ строку из файла
4. Если файл не найден - попробуй другой запрос к find_file

### Примеры:

**Шаг 1 - Найти файл по имени (ОБЯЗАТЕЛЬНО первый шаг!):**
{
  "title": "Ищу файл",
  "message": "Ищу файл с авторизацией",
  "tool_calls": [
    {"name": "find_file", "arguments": {"query": "AuthScreen"}}
  ]
}

**Шаг 2 - Прочитать найденный файл:**
{
  "title": "Читаю файл",
  "message": "Читаю содержимое файла",
  "tool_calls": [
    {"name": "read_file", "arguments": {"path": "app/src/main/java/com/example/auth/AuthScreen.kt"}}
  ]
}

**Шаг 3 - Редактировать с ТОЧНОЙ строкой из файла:**
{
  "title": "Меняю цвет",
  "message": "Меняю цвет кнопки",
  "tool_calls": [
    {"name": "edit_file", "arguments": {"path": "app/src/main/java/com/example/auth/AuthScreen.kt", "old_string": "color = Color.Blue", "new_string": "color = Color.Green"}}
  ]
}

**Поиск кода по содержимому:**
{
  "title": "Ищу код",
  "message": "Ищу где используется синий цвет",
  "tool_calls": [
    {"name": "grep", "arguments": {"pattern": "Color\\.Blue|#2196F3", "glob": "**/*.kt"}}
  ]
}
        """.trimIndent()
    }
}
