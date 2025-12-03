package kz.shprot.tools.impl

import kotlinx.serialization.json.*
import kz.shprot.tools.*
import java.io.File

/**
 * Инструмент для чтения файлов.
 * Поддерживает чтение части файла (offset/limit) для больших файлов.
 */
class ReadFileTool : Tool {
    override val name = "read_file"

    override val description = """
        Читает содержимое файла из проекта.

        ВАЖНО:
        - path должен быть путём в файловой системе, НЕ Java package!
        - Пример правильного пути: app/src/main/java/com/example/MainActivity.kt
        - Пример НЕПРАВИЛЬНОГО пути: com.example.MainActivity.kt

        Для больших файлов используй offset и limit.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(
                type = "string",
                description = "Путь к файлу относительно корня проекта"
            ),
            "offset" to ToolParameter(
                type = "integer",
                description = "Номер строки, с которой начать чтение (0-based)",
                default = JsonPrimitive(0)
            ),
            "limit" to ToolParameter(
                type = "integer",
                description = "Максимальное количество строк для чтения. 0 = все строки",
                default = JsonPrimitive(0)
            )
        ),
        required = listOf("path")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = parameters["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'path' is required")

        val offset = parameters["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = parameters["limit"]?.jsonPrimitive?.intOrNull ?: 0

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val file = File(projectRoot, path)

        // Проверка безопасности пути
        val canonicalPath = file.canonicalPath
        if (!canonicalPath.startsWith(File(projectRoot).canonicalPath)) {
            return ToolResult.Error("Access denied: path is outside project directory")
        }

        if (!file.exists()) {
            return ToolResult.Error("File not found: $path")
        }

        if (!file.isFile) {
            return ToolResult.Error("Path is not a file: $path")
        }

        return runCatching {
            val lines = file.readLines()
            val totalLines = lines.size

            val content = if (limit > 0) {
                lines.drop(offset).take(limit).joinToString("\n")
            } else {
                lines.drop(offset).joinToString("\n")
            }

            val readLines = if (limit > 0) minOf(limit, totalLines - offset) else totalLines - offset

            ToolResult.Success(
                output = content,
                metadata = mapOf(
                    "path" to path,
                    "totalLines" to totalLines.toString(),
                    "readLines" to readLines.toString(),
                    "offset" to offset.toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to read file: ${e.message}")
        }
    }
}

/**
 * Инструмент для записи в файлы.
 * Поддерживает создание новых файлов и перезапись существующих.
 */
class WriteFileTool : Tool {
    override val name = "write_file"

    override val description = """
        Записывает содержимое в файл.
        Создаёт файл если он не существует.
        Может перезаписать существующий файл или добавить в конец.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(
                type = "string",
                description = "Путь к файлу относительно корня проекта"
            ),
            "content" to ToolParameter(
                type = "string",
                description = "Содержимое для записи"
            ),
            "mode" to ToolParameter(
                type = "string",
                description = "Режим записи: 'overwrite' (перезаписать) или 'append' (добавить в конец)",
                enum = listOf("overwrite", "append"),
                default = JsonPrimitive("overwrite")
            )
        ),
        required = listOf("path", "content")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = parameters["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'path' is required")

        val content = parameters["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'content' is required")

        val mode = parameters["mode"]?.jsonPrimitive?.content ?: "overwrite"

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val file = File(projectRoot, path)

        // Проверка безопасности пути
        val canonicalPath = file.canonicalPath
        if (!canonicalPath.startsWith(File(projectRoot).canonicalPath)) {
            return ToolResult.Error("Access denied: path is outside project directory")
        }

        return runCatching {
            // Создаём родительские директории если нужно
            file.parentFile?.mkdirs()

            val existed = file.exists()
            val previousSize = if (existed) file.length() else 0

            when (mode) {
                "append" -> file.appendText(content)
                else -> file.writeText(content)
            }

            ToolResult.Success(
                output = if (existed) {
                    "File updated: $path"
                } else {
                    "File created: $path"
                },
                metadata = mapOf(
                    "path" to path,
                    "mode" to mode,
                    "existed" to existed.toString(),
                    "previousSize" to previousSize.toString(),
                    "newSize" to file.length().toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to write file: ${e.message}")
        }
    }
}

/**
 * Инструмент для редактирования файлов (замена текста).
 * Аналог Edit tool в Claude Code.
 */
class EditFileTool : Tool {
    override val name = "edit_file"

    override val description = """
        Редактирует файл, заменяя указанный текст на новый.

        ВАЖНО:
        - path должен быть путём в файловой системе, НЕ Java package!
        - old_string НЕ МОЖЕТ быть пустым! Минимум 5 символов.
        - old_string должен быть ТОЧНОЙ копией из файла (включая пробелы/отступы)

        Перед редактированием ОБЯЗАТЕЛЬНО:
        1. Найди файл через find_file
        2. Прочитай файл через read_file
        3. Скопируй ТОЧНУЮ строку для old_string

        Чтобы ДОБАВИТЬ новый параметр в вызов функции:
        old_string: "ButtonDefault("
        new_string: "ButtonDefault(\n            containerColor = Color.Green,"

        Чтобы ЗАМЕНИТЬ значение:
        old_string: "containerColor = Color.Blue"
        new_string: "containerColor = Color.Green"
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(
                type = "string",
                description = "Путь к файлу относительно корня проекта"
            ),
            "old_string" to ToolParameter(
                type = "string",
                description = "Текст для замены (должен быть уникальным в файле)"
            ),
            "new_string" to ToolParameter(
                type = "string",
                description = "Новый текст"
            ),
            "replace_all" to ToolParameter(
                type = "boolean",
                description = "Заменить все вхождения (по умолчанию false)",
                default = JsonPrimitive(false)
            )
        ),
        required = listOf("path", "old_string", "new_string")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = parameters["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'path' is required")

        val oldString = parameters["old_string"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'old_string' is required")

        val newString = parameters["new_string"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'new_string' is required")

        val replaceAll = parameters["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false

        // ЗАЩИТА: Пустой old_string недопустим - это уничтожит файл!
        if (oldString.isBlank()) {
            return ToolResult.Error(
                "old_string cannot be empty or blank! " +
                "To ADD new code, use old_string with the line BEFORE which you want to insert, " +
                "and include that line in new_string along with your addition. " +
                "Example: old_string='ButtonDefault(' new_string='ButtonDefault(\\n    containerColor = Color.Green,'"
            )
        }

        // ЗАЩИТА: old_string должен быть достаточно длинным для уникальности
        if (oldString.length < 5) {
            return ToolResult.Error(
                "old_string is too short (${oldString.length} chars). " +
                "Provide at least 5 characters to ensure unique match."
            )
        }

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val file = File(projectRoot, path)

        // Проверка безопасности пути
        val canonicalPath = file.canonicalPath
        if (!canonicalPath.startsWith(File(projectRoot).canonicalPath)) {
            return ToolResult.Error("Access denied: path is outside project directory")
        }

        if (!file.exists()) {
            return ToolResult.Error("File not found: $path")
        }

        return runCatching {
            val content = file.readText()
            val occurrences = content.split(oldString).size - 1

            if (occurrences == 0) {
                return@runCatching ToolResult.Error("old_string not found in file. Read the file first to see exact content.")
            }

            // ЗАЩИТА: Слишком много замен - опасно
            if (occurrences > 50) {
                return@runCatching ToolResult.Error(
                    "old_string found $occurrences times - too many! " +
                    "Provide more specific/unique old_string to avoid mass replacement."
                )
            }

            if (occurrences > 1 && !replaceAll) {
                return@runCatching ToolResult.Error(
                    "old_string found $occurrences times. Set replace_all=true to replace all, " +
                            "or provide more context to make it unique."
                )
            }

            val newContent = if (replaceAll) {
                content.replace(oldString, newString)
            } else {
                content.replaceFirst(oldString, newString)
            }

            file.writeText(newContent)

            ToolResult.Success(
                output = "File edited: $path (replaced $occurrences occurrence(s))",
                metadata = mapOf(
                    "path" to path,
                    "replacements" to occurrences.toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to edit file: ${e.message}")
        }
    }
}

/**
 * Инструмент для поиска файлов по имени.
 * Ищет файлы, имя которых содержит указанную строку.
 */
class FindFileTool : Tool {
    override val name = "find_file"

    override val description = """
        Ищет файлы по имени (или части имени) в проекте.
        Используй этот инструмент когда нужно найти файл по названию.
        Поиск регистронезависимый.

        Примеры:
        - query: "LoginActivity" - найдёт LoginActivity.kt, LoginActivityTest.kt
        - query: "auth" - найдёт все файлы со словом auth в названии
        - query: "ScreenContent" - найдёт AuthScreenContent.kt, HomeScreenContent.kt
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "query" to ToolParameter(
                type = "string",
                description = "Строка для поиска в имени файла (регистронезависимый)"
            ),
            "extensions" to ToolParameter(
                type = "array",
                description = "Фильтр по расширениям файлов (например: [\"kt\", \"xml\"])",
                default = JsonNull
            ),
            "max_results" to ToolParameter(
                type = "integer",
                description = "Максимальное количество результатов",
                default = JsonPrimitive(20)
            )
        ),
        required = listOf("query")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val query = parameters["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'query' is required")

        val extensions = parameters["extensions"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.lowercase()
        } ?: emptyList()

        val maxResults = parameters["max_results"]?.jsonPrimitive?.intOrNull ?: 20

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        return runCatching {
            val rootDir = File(projectRoot)
            val queryLower = query.lowercase()

            // Исключаем build директории и другой мусор
            val excludeDirs = setOf("build", ".gradle", ".idea", ".git", "node_modules", ".kotlin")

            val files = rootDir.walkTopDown()
                .onEnter { dir -> dir.name !in excludeDirs }
                .filter { it.isFile }
                .filter { file ->
                    file.name.lowercase().contains(queryLower)
                }
                .filter { file ->
                    extensions.isEmpty() || file.extension.lowercase() in extensions
                }
                .take(maxResults)
                .map { file ->
                    val relativePath = file.relativeTo(rootDir).path
                    relativePath
                }
                .toList()

            ToolResult.Success(
                output = if (files.isEmpty()) {
                    "No files found with '$query' in name"
                } else {
                    "Found ${files.size} file(s):\n" + files.joinToString("\n")
                },
                metadata = mapOf(
                    "query" to query,
                    "count" to files.size.toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to find files: ${e.message}")
        }
    }
}

/**
 * Инструмент для поиска файлов по паттерну (glob).
 */
class SearchFilesTool : Tool {
    override val name = "search_files"

    override val description = """
        Ищет файлы в проекте по glob паттерну.
        Используй для поиска файлов по структуре директорий.

        ВАЖНО: Это glob паттерн, не имя файла!
        Для поиска по имени используй find_file.

        Примеры паттернов:
        - **/*.kt - все Kotlin файлы во всех папках
        - app/src/main/**/*.kt - Kotlin файлы в main
        - **/auth/**/*.kt - Kotlin файлы в папках auth
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "pattern" to ToolParameter(
                type = "string",
                description = "Glob паттерн (например: **/*.kt, **/auth/**/*.xml)"
            ),
            "max_results" to ToolParameter(
                type = "integer",
                description = "Максимальное количество результатов",
                default = JsonPrimitive(50)
            )
        ),
        required = listOf("pattern")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val pattern = parameters["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'pattern' is required")

        val maxResults = parameters["max_results"]?.jsonPrimitive?.intOrNull ?: 50

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        return runCatching {
            val rootDir = File(projectRoot)
            val matcher = createGlobMatcher(pattern)

            val files = rootDir.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    val relativePath = file.relativeTo(rootDir).path
                    matcher(relativePath)
                }
                .take(maxResults)
                .map { it.relativeTo(rootDir).path }
                .toList()

            ToolResult.Success(
                output = if (files.isEmpty()) {
                    "No files found matching pattern: $pattern"
                } else {
                    files.joinToString("\n")
                },
                metadata = mapOf(
                    "pattern" to pattern,
                    "count" to files.size.toString(),
                    "truncated" to (files.size >= maxResults).toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to search files: ${e.message}")
        }
    }

    private fun createGlobMatcher(pattern: String): (String) -> Boolean {
        // Простая реализация glob matcher
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("**", "<<<DOUBLESTAR>>>")
            .replace("*", "[^/]*")
            .replace("<<<DOUBLESTAR>>>", ".*")
            .replace("?", ".")

        val regex = Regex("^$regexPattern$")
        return { path -> regex.matches(path.replace("\\", "/")) }
    }
}

/**
 * Инструмент для поиска текста в файлах (grep).
 */
class GrepTool : Tool {
    override val name = "grep"

    override val description = """
        Ищет текст (или regex) в файлах проекта.
        Возвращает совпадения с номерами строк.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "pattern" to ToolParameter(
                type = "string",
                description = "Текст или regex паттерн для поиска"
            ),
            "glob" to ToolParameter(
                type = "string",
                description = "Glob паттерн для фильтрации файлов (например, **/*.kt)",
                default = JsonPrimitive("**/*")
            ),
            "case_insensitive" to ToolParameter(
                type = "boolean",
                description = "Игнорировать регистр",
                default = JsonPrimitive(false)
            ),
            "max_results" to ToolParameter(
                type = "integer",
                description = "Максимальное количество совпадений",
                default = JsonPrimitive(100)
            ),
            "context_lines" to ToolParameter(
                type = "integer",
                description = "Количество строк контекста до и после совпадения",
                default = JsonPrimitive(0)
            )
        ),
        required = listOf("pattern")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val pattern = parameters["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'pattern' is required")

        val glob = parameters["glob"]?.jsonPrimitive?.content ?: "**/*"
        val caseInsensitive = parameters["case_insensitive"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxResults = parameters["max_results"]?.jsonPrimitive?.intOrNull ?: 100
        val contextLines = parameters["context_lines"]?.jsonPrimitive?.intOrNull ?: 0

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        return runCatching {
            val rootDir = File(projectRoot)
            val globMatcher = createGlobMatcher(glob)
            val regexOptions = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = Regex(pattern, regexOptions)

            val results = mutableListOf<String>()
            var totalMatches = 0

            rootDir.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    val relativePath = file.relativeTo(rootDir).path
                    globMatcher(relativePath) && !isBinaryFile(file)
                }
                .forEach { file ->
                    if (totalMatches >= maxResults) return@forEach

                    val relativePath = file.relativeTo(rootDir).path
                    val lines = file.readLines()

                    lines.forEachIndexed { index, line ->
                        if (totalMatches >= maxResults) return@forEachIndexed

                        if (regex.containsMatchIn(line)) {
                            val lineNum = index + 1 // 1-based

                            if (contextLines > 0) {
                                results.add("$relativePath:$lineNum:")
                                val start = maxOf(0, index - contextLines)
                                val end = minOf(lines.size, index + contextLines + 1)
                                for (i in start until end) {
                                    val prefix = if (i == index) ">" else " "
                                    results.add("$prefix ${i + 1}: ${lines[i]}")
                                }
                                results.add("")
                            } else {
                                results.add("$relativePath:$lineNum: $line")
                            }

                            totalMatches++
                        }
                    }
                }

            ToolResult.Success(
                output = if (results.isEmpty()) {
                    "No matches found for pattern: $pattern"
                } else {
                    results.joinToString("\n")
                },
                metadata = mapOf(
                    "pattern" to pattern,
                    "matches" to totalMatches.toString(),
                    "truncated" to (totalMatches >= maxResults).toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to search: ${e.message}")
        }
    }

    private fun createGlobMatcher(pattern: String): (String) -> Boolean {
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("**", "<<<DOUBLESTAR>>>")
            .replace("*", "[^/]*")
            .replace("<<<DOUBLESTAR>>>", ".*")
            .replace("?", ".")

        val regex = Regex("^$regexPattern$")
        return { path -> regex.matches(path.replace("\\", "/")) }
    }

    private fun isBinaryFile(file: File): Boolean {
        val binaryExtensions = setOf(
            "jar", "class", "so", "dylib", "dll", "exe",
            "png", "jpg", "jpeg", "gif", "ico", "webp",
            "mp3", "mp4", "avi", "mov", "wav",
            "zip", "tar", "gz", "rar", "7z",
            "pdf", "doc", "docx", "xls", "xlsx"
        )
        return file.extension.lowercase() in binaryExtensions
    }
}

/**
 * Инструмент для просмотра структуры директории.
 */
class ListDirectoryTool : Tool {
    override val name = "list_directory"

    override val description = """
        Показывает содержимое директории (файлы и папки).
        Можно указать глубину рекурсии.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(
                type = "string",
                description = "Путь к директории относительно корня проекта",
                default = JsonPrimitive(".")
            ),
            "depth" to ToolParameter(
                type = "integer",
                description = "Максимальная глубина рекурсии (0 = только текущая папка)",
                default = JsonPrimitive(1)
            ),
            "show_hidden" to ToolParameter(
                type = "boolean",
                description = "Показывать скрытые файлы (начинающиеся с точки)",
                default = JsonPrimitive(false)
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = parameters["path"]?.jsonPrimitive?.content ?: "."
        val depth = parameters["depth"]?.jsonPrimitive?.intOrNull ?: 1
        val showHidden = parameters["show_hidden"]?.jsonPrimitive?.booleanOrNull ?: false

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val targetDir = File(projectRoot, path)

        // Проверка безопасности пути
        val canonicalPath = targetDir.canonicalPath
        if (!canonicalPath.startsWith(File(projectRoot).canonicalPath)) {
            return ToolResult.Error("Access denied: path is outside project directory")
        }

        if (!targetDir.exists()) {
            return ToolResult.Error("Directory not found: $path")
        }

        if (!targetDir.isDirectory) {
            return ToolResult.Error("Path is not a directory: $path")
        }

        return runCatching {
            val builder = StringBuilder()
            listDirectoryRecursive(targetDir, targetDir, depth, showHidden, builder, "")

            ToolResult.Success(
                output = builder.toString(),
                metadata = mapOf(
                    "path" to path,
                    "depth" to depth.toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to list directory: ${e.message}")
        }
    }

    private fun listDirectoryRecursive(
        root: File,
        dir: File,
        remainingDepth: Int,
        showHidden: Boolean,
        builder: StringBuilder,
        prefix: String
    ) {
        val children = dir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return

        children.forEachIndexed { index, file ->
            val isLast = index == children.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val icon = if (file.isDirectory) "📁 " else "📄 "

            builder.appendLine("$prefix$connector$icon${file.name}")

            if (file.isDirectory && remainingDepth > 0) {
                val newPrefix = prefix + if (isLast) "    " else "│   "
                listDirectoryRecursive(root, file, remainingDepth - 1, showHidden, builder, newPrefix)
            }
        }
    }
}
