package kz.shprot.commands

import kotlinx.serialization.Serializable
import kz.shprot.RAGManager
import kz.shprot.tools.ProjectManager
import kz.shprot.tools.ToolRegistry

/**
 * Обработчик slash-команд.
 * Поддерживает команды вида /help, /project, /tools и т.д.
 */
class CommandHandler(
    private val toolRegistry: ToolRegistry,
    private val projectManager: ProjectManager,
    private val ragManager: RAGManager? = null
) {
    private val commands = mutableMapOf<String, Command>()

    init {
        registerDefaultCommands()
    }

    /**
     * Описание команды.
     */
    data class Command(
        val name: String,
        val description: String,
        val usage: String,
        val handler: suspend (args: List<String>) -> CommandResult
    )

    /**
     * Результат выполнения команды.
     */
    @Serializable
    sealed class CommandResult {
        @Serializable
        data class Success(
            val output: String,
            val isMarkdown: Boolean = true
        ) : CommandResult()

        @Serializable
        data class Error(val message: String) : CommandResult()

        @Serializable
        data class NotACommand(val originalMessage: String) : CommandResult()
    }

    /**
     * Регистрирует команду.
     */
    fun register(command: Command) {
        commands[command.name.lowercase()] = command
    }

    /**
     * Проверяет, является ли сообщение командой.
     */
    fun isCommand(message: String): Boolean {
        return message.trim().startsWith("/")
    }

    /**
     * Обрабатывает сообщение.
     * Если это команда - выполняет её, иначе возвращает NotACommand.
     */
    suspend fun handle(message: String): CommandResult {
        val trimmed = message.trim()

        if (!trimmed.startsWith("/")) {
            return CommandResult.NotACommand(message)
        }

        val parts = trimmed.drop(1).split(Regex("\\s+"), limit = 2)
        val commandName = parts[0].lowercase()
        val args = if (parts.size > 1) {
            parts[1].split(Regex("\\s+"))
        } else {
            emptyList()
        }

        val command = commands[commandName]
            ?: return CommandResult.Error("Unknown command: /$commandName. Use /help to see available commands.")

        return runCatching {
            command.handler(args)
        }.getOrElse { e ->
            CommandResult.Error("Command failed: ${e.message}")
        }
    }

    /**
     * Возвращает список всех команд.
     */
    fun getCommands(): List<Command> = commands.values.toList()

    private fun registerDefaultCommands() {
        // /help - показать справку
        register(Command(
            name = "help",
            description = "Показывает справку по командам и проекту",
            usage = "/help [topic]",
            handler = { args ->
                val topic = args.firstOrNull()
                when (topic) {
                    null, "commands" -> showCommandsHelp()
                    "tools" -> showToolsHelp()
                    "project" -> showProjectHelp()
                    "structure" -> showStructureHelp()
                    else -> showTopicHelp(topic)
                }
            }
        ))

        // /project - управление проектами
        register(Command(
            name = "project",
            description = "Управление проектами",
            usage = "/project [list|select|info|register]",
            handler = { args ->
                when (args.firstOrNull()) {
                    "list", null -> listProjects()
                    "select" -> selectProject(args.drop(1))
                    "info" -> projectInfo()
                    "register" -> registerProject(args.drop(1))
                    else -> CommandResult.Error("Unknown subcommand. Use: list, select, info, register")
                }
            }
        ))

        // /tools - список инструментов
        register(Command(
            name = "tools",
            description = "Показывает список доступных инструментов",
            usage = "/tools [category]",
            handler = { args ->
                val category = args.firstOrNull()
                showTools(category)
            }
        ))

        // /branch - текущая ветка git
        register(Command(
            name = "branch",
            description = "Показывает текущую ветку git",
            usage = "/branch",
            handler = {
                val branch = projectManager.getGitBranch()
                if (branch != null) {
                    CommandResult.Success("Current branch: **$branch**")
                } else {
                    CommandResult.Error("Not in a git repository or no project selected")
                }
            }
        ))

        // /readme - показать README проекта
        register(Command(
            name = "readme",
            description = "Показывает README текущего проекта",
            usage = "/readme",
            handler = {
                val content = projectManager.getReadmeContent()
                if (content != null) {
                    CommandResult.Success(content)
                } else {
                    CommandResult.Error("README not found in current project")
                }
            }
        ))

        // /docs - список документации
        register(Command(
            name = "docs",
            description = "Показывает список файлов документации проекта",
            usage = "/docs",
            handler = {
                val docs = projectManager.getDocsFiles()
                if (docs.isNotEmpty()) {
                    val output = buildString {
                        appendLine("## Documentation files")
                        appendLine()
                        docs.forEach { appendLine("- `$it`") }
                    }
                    CommandResult.Success(output)
                } else {
                    CommandResult.Error("No documentation files found")
                }
            }
        ))

        // /clear - очистка (заглушка для UI)
        register(Command(
            name = "clear",
            description = "Очищает историю чата",
            usage = "/clear",
            handler = {
                CommandResult.Success("CLEAR_CHAT", isMarkdown = false)
            }
        ))
    }

    private fun showCommandsHelp(): CommandResult {
        val output = buildString {
            appendLine("# Available Commands")
            appendLine()
            commands.values.sortedBy { it.name }.forEach { cmd ->
                appendLine("## /${cmd.name}")
                appendLine(cmd.description)
                appendLine()
                appendLine("Usage: `${cmd.usage}`")
                appendLine()
            }
            appendLine("---")
            appendLine("**Help topics:** `/help commands`, `/help tools`, `/help project`, `/help structure`")
        }
        return CommandResult.Success(output)
    }

    private fun showToolsHelp(): CommandResult {
        val output = toolRegistry.toSystemPromptDescription()
        return CommandResult.Success(output)
    }

    private fun showProjectHelp(): CommandResult {
        val project = projectManager.getCurrentProject()
        val output = if (project != null) {
            buildString {
                appendLine("# Current Project: ${project.name}")
                appendLine()
                appendLine("- **ID:** ${project.id}")
                appendLine("- **Type:** ${project.type}")
                appendLine("- **Path:** `${project.rootPath}`")
                projectManager.getGitBranch()?.let {
                    appendLine("- **Git branch:** $it")
                }
                appendLine()
                appendLine("Use `/readme` to view project README")
                appendLine("Use `/docs` to list documentation files")
            }
        } else {
            "No project selected. Use `/project list` to see available projects."
        }
        return CommandResult.Success(output)
    }

    private fun showStructureHelp(): CommandResult {
        val project = projectManager.getCurrentProject()
            ?: return CommandResult.Error("No project selected")

        val output = buildString {
            appendLine("# Project Structure: ${project.name}")
            appendLine()
            appendLine("**Type:** ${project.type}")
            appendLine()

            when (project.type) {
                ProjectManager.ProjectType.ANDROID -> {
                    appendLine("## Android Project Structure")
                    appendLine("```")
                    appendLine("app/")
                    appendLine("├── src/main/")
                    appendLine("│   ├── java/kotlin/    # Source code")
                    appendLine("│   ├── res/            # Resources (layouts, strings, etc.)")
                    appendLine("│   └── AndroidManifest.xml")
                    appendLine("├── build.gradle.kts    # Module build config")
                    appendLine("└── proguard-rules.pro")
                    appendLine("build.gradle.kts        # Project build config")
                    appendLine("settings.gradle.kts     # Project settings")
                    appendLine("```")
                }
                ProjectManager.ProjectType.KOTLIN -> {
                    appendLine("## Kotlin Project Structure")
                    appendLine("```")
                    appendLine("src/")
                    appendLine("├── main/kotlin/        # Source code")
                    appendLine("├── main/resources/     # Resources")
                    appendLine("└── test/kotlin/        # Tests")
                    appendLine("build.gradle.kts        # Build config")
                    appendLine("```")
                }
                else -> {
                    appendLine("Use `/project info` for more details")
                }
            }
        }
        return CommandResult.Success(output)
    }

    private suspend fun showTopicHelp(topic: String): CommandResult {
        // Попробуем найти в RAG
        if (ragManager != null) {
            val context = runCatching {
                // Здесь можно использовать RAG для поиска
                // Пока просто возвращаем заглушку
                null
            }.getOrNull()

            if (context != null) {
                return CommandResult.Success(context)
            }
        }

        return CommandResult.Error("Unknown help topic: $topic. Use /help to see available topics.")
    }

    private fun listProjects(): CommandResult {
        val projects = projectManager.getAllProjects()
        val current = projectManager.getCurrentProject()

        if (projects.isEmpty()) {
            return CommandResult.Success(
                "No projects registered.\n\nUse `/project register <path>` to add a project."
            )
        }

        val output = buildString {
            appendLine("# Registered Projects")
            appendLine()
            projects.forEach { project ->
                val marker = if (project.id == current?.id) "▶ " else "  "
                appendLine("$marker**${project.name}** (`${project.id}`)")
                appendLine("   Type: ${project.type}, Path: `${project.rootPath}`")
            }
            appendLine()
            appendLine("Use `/project select <id>` to switch projects")
        }
        return CommandResult.Success(output)
    }

    private fun selectProject(args: List<String>): CommandResult {
        val projectId = args.firstOrNull()
            ?: return CommandResult.Error("Usage: /project select <project_id>")

        return projectManager.switchProject(projectId).fold(
            onSuccess = { project ->
                CommandResult.Success("Switched to project: **${project.name}**\n\nPath: `${project.rootPath}`")
            },
            onFailure = { e ->
                CommandResult.Error("Failed to switch project: ${e.message}")
            }
        )
    }

    private fun projectInfo(): CommandResult {
        val project = projectManager.getCurrentProject()
            ?: return CommandResult.Error("No project selected. Use `/project list` first.")

        val output = buildString {
            appendLine("# ${project.name}")
            appendLine()
            appendLine("| Property | Value |")
            appendLine("|----------|-------|")
            appendLine("| ID | `${project.id}` |")
            appendLine("| Type | ${project.type} |")
            appendLine("| Path | `${project.rootPath}` |")
            projectManager.getGitBranch()?.let {
                appendLine("| Git Branch | `$it` |")
            }
            project.readmePath?.let {
                appendLine("| README | `$it` |")
            }
            project.docsPath?.let {
                appendLine("| Docs | `$it` |")
            }
        }
        return CommandResult.Success(output)
    }

    private fun registerProject(args: List<String>): CommandResult {
        val path = args.firstOrNull()
            ?: return CommandResult.Error("Usage: /project register <path> [name]")

        val name = args.getOrNull(1)

        val project = ProjectManager.createProjectFromPath(path, name)

        return projectManager.registerProject(project).fold(
            onSuccess = {
                CommandResult.Success(
                    "Project registered: **${project.name}**\n\n" +
                            "- ID: `${project.id}`\n" +
                            "- Type: ${project.type} (auto-detected)\n" +
                            "- Path: `${project.rootPath}`"
                )
            },
            onFailure = { e ->
                CommandResult.Error("Failed to register: ${e.message}")
            }
        )
    }

    private fun showTools(category: String?): CommandResult {
        val tools = if (category != null) {
            toolRegistry.getByCategory(category)
        } else {
            toolRegistry.getAll()
        }

        if (tools.isEmpty()) {
            return CommandResult.Error(
                if (category != null) "No tools in category: $category"
                else "No tools registered"
            )
        }

        val output = buildString {
            appendLine("# Available Tools" + (category?.let { " ($it)" } ?: ""))
            appendLine()
            tools.forEach { tool ->
                appendLine("## ${tool.name}")
                appendLine(tool.description.trim())
                appendLine()
            }

            val categories = toolRegistry.getCategories()
            if (categories.isNotEmpty() && category == null) {
                appendLine("---")
                appendLine("**Categories:** ${categories.joinToString(", ")}")
                appendLine("Use `/tools <category>` to filter by category")
            }
        }
        return CommandResult.Success(output)
    }
}
