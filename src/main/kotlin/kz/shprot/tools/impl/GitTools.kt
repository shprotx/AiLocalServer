package kz.shprot.tools.impl

import kotlinx.serialization.json.*
import kz.shprot.tools.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Инструмент для получения текущей ветки git.
 */
class GitBranchTool : Tool {
    override val name = "git_branch"

    override val description = """
        Получает информацию о текущей ветке git.
        Возвращает имя текущей ветки и список всех веток.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "list_all" to ToolParameter(
                type = "boolean",
                description = "Показать все ветки (локальные и удалённые)",
                default = JsonPrimitive(false)
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val listAll = parameters["list_all"]?.jsonPrimitive?.booleanOrNull ?: false

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            return ToolResult.Error("Not a git repository: $projectRoot")
        }

        return runCatching {
            // Получаем текущую ветку из HEAD
            val headFile = File(gitDir, "HEAD")
            val currentBranch = if (headFile.exists()) {
                val content = headFile.readText().trim()
                if (content.startsWith("ref: refs/heads/")) {
                    content.removePrefix("ref: refs/heads/")
                } else {
                    "HEAD detached at ${content.take(7)}"
                }
            } else {
                "unknown"
            }

            // Получаем список веток
            val branches = mutableListOf<String>()
            val headsDir = File(gitDir, "refs/heads")
            if (headsDir.exists()) {
                headsDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val branchName = file.relativeTo(headsDir).path
                        val marker = if (branchName == currentBranch) "* " else "  "
                        branches.add("$marker$branchName")
                    }
            }

            // Удалённые ветки
            if (listAll) {
                val remotesDir = File(gitDir, "refs/remotes")
                if (remotesDir.exists()) {
                    remotesDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val branchName = "remotes/" + file.relativeTo(remotesDir).path
                            branches.add("  $branchName")
                        }
                }
            }

            val output = buildString {
                appendLine("Current branch: $currentBranch")
                appendLine()
                appendLine("Branches:")
                branches.sorted().forEach { appendLine(it) }
            }

            ToolResult.Success(
                output = output,
                metadata = mapOf(
                    "currentBranch" to currentBranch,
                    "branchCount" to branches.size.toString()
                )
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to get git branch info: ${e.message}")
        }
    }
}

/**
 * Инструмент для получения статуса git.
 */
class GitStatusTool : Tool {
    override val name = "git_status"

    override val description = """
        Получает статус git репозитория.
        Показывает изменённые, добавленные и удалённые файлы.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            return ToolResult.Error("Not a git repository: $projectRoot")
        }

        return runCatching {
            val result = executeGitCommand(projectRoot, "status", "--porcelain")

            if (result.isBlank()) {
                ToolResult.Success(
                    output = "Working tree clean - no changes",
                    metadata = mapOf("changedFiles" to "0")
                )
            } else {
                val lines = result.lines().filter { it.isNotBlank() }
                val staged = lines.filter { it.startsWith("A ") || it.startsWith("M ") || it.startsWith("D ") }
                val modified = lines.filter { it.startsWith(" M") || it.startsWith("MM") }
                val untracked = lines.filter { it.startsWith("??") }

                val output = buildString {
                    if (staged.isNotEmpty()) {
                        appendLine("Staged changes:")
                        staged.forEach { appendLine("  ${parseStatusLine(it)}") }
                        appendLine()
                    }
                    if (modified.isNotEmpty()) {
                        appendLine("Modified (not staged):")
                        modified.forEach { appendLine("  ${parseStatusLine(it)}") }
                        appendLine()
                    }
                    if (untracked.isNotEmpty()) {
                        appendLine("Untracked files:")
                        untracked.forEach { appendLine("  ${parseStatusLine(it)}") }
                    }
                }

                ToolResult.Success(
                    output = output.ifBlank { result },
                    metadata = mapOf(
                        "staged" to staged.size.toString(),
                        "modified" to modified.size.toString(),
                        "untracked" to untracked.size.toString()
                    )
                )
            }
        }.getOrElse { e ->
            ToolResult.Error("Failed to get git status: ${e.message}")
        }
    }

    private fun parseStatusLine(line: String): String {
        val status = line.take(2)
        val file = line.drop(3)
        val statusText = when {
            status.startsWith("A") -> "[added]"
            status.startsWith("M") || status.endsWith("M") -> "[modified]"
            status.startsWith("D") -> "[deleted]"
            status == "??" -> "[untracked]"
            status.startsWith("R") -> "[renamed]"
            else -> "[$status]"
        }
        return "$statusText $file"
    }
}

/**
 * Инструмент для просмотра git diff.
 */
class GitDiffTool : Tool {
    override val name = "git_diff"

    override val description = """
        Показывает diff изменений в файлах.
        Можно указать конкретный файл или посмотреть все изменения.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(
                type = "string",
                description = "Путь к файлу (опционально, без указания - все файлы)"
            ),
            "staged" to ToolParameter(
                type = "boolean",
                description = "Показать staged изменения (по умолчанию - unstaged)",
                default = JsonPrimitive(false)
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = parameters["path"]?.jsonPrimitive?.content
        val staged = parameters["staged"]?.jsonPrimitive?.booleanOrNull ?: false

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            return ToolResult.Error("Not a git repository: $projectRoot")
        }

        return runCatching {
            val args = mutableListOf("diff")
            if (staged) args.add("--cached")
            path?.let { args.add(it) }

            val result = executeGitCommand(projectRoot, *args.toTypedArray())

            if (result.isBlank()) {
                ToolResult.Success(
                    output = "No changes" + (if (staged) " (staged)" else ""),
                    metadata = mapOf("hasChanges" to "false")
                )
            } else {
                ToolResult.Success(
                    output = result,
                    metadata = mapOf("hasChanges" to "true")
                )
            }
        }.getOrElse { e ->
            ToolResult.Error("Failed to get git diff: ${e.message}")
        }
    }
}

/**
 * Инструмент для просмотра git log.
 */
class GitLogTool : Tool {
    override val name = "git_log"

    override val description = """
        Показывает историю коммитов.
        Возвращает последние N коммитов с сообщениями.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "count" to ToolParameter(
                type = "integer",
                description = "Количество коммитов для отображения",
                default = JsonPrimitive(10)
            ),
            "path" to ToolParameter(
                type = "string",
                description = "Путь к файлу для фильтрации истории (опционально)"
            ),
            "oneline" to ToolParameter(
                type = "boolean",
                description = "Компактный формат (одна строка на коммит)",
                default = JsonPrimitive(true)
            )
        ),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val count = parameters["count"]?.jsonPrimitive?.intOrNull ?: 10
        val path = parameters["path"]?.jsonPrimitive?.content
        val oneline = parameters["oneline"]?.jsonPrimitive?.booleanOrNull ?: true

        val projectRoot = context.projectRoot
            ?: return ToolResult.Error("No project selected. Use 'select_project' first.")

        val gitDir = File(projectRoot, ".git")
        if (!gitDir.exists()) {
            return ToolResult.Error("Not a git repository: $projectRoot")
        }

        return runCatching {
            val args = mutableListOf("log", "-n", count.toString())
            if (oneline) {
                args.add("--oneline")
            } else {
                args.addAll(listOf("--pretty=format:%h %ad | %s [%an]", "--date=short"))
            }
            path?.let {
                args.add("--")
                args.add(it)
            }

            val result = executeGitCommand(projectRoot, *args.toTypedArray())

            ToolResult.Success(
                output = result.ifBlank { "No commits found" },
                metadata = mapOf("count" to count.toString())
            )
        }.getOrElse { e ->
            ToolResult.Error("Failed to get git log: ${e.message}")
        }
    }
}

/**
 * Выполняет git команду и возвращает результат.
 */
private fun executeGitCommand(workingDir: String, vararg args: String): String {
    val command = listOf("git") + args.toList()
    val process = ProcessBuilder(command)
        .directory(File(workingDir))
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        throw RuntimeException("Git command failed with exit code $exitCode: $output")
    }

    return output.trim()
}
