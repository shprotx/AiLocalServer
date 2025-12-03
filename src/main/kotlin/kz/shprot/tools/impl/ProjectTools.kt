package kz.shprot.tools.impl

import kotlinx.serialization.json.*
import kz.shprot.tools.*

/**
 * Инструмент для выбора/переключения проекта.
 */
class SelectProjectTool(private val projectManager: ProjectManager) : Tool {
    override val name = "select_project"

    override val description = """
        Выбирает проект для работы.
        После выбора все файловые операции будут относиться к этому проекту.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "project_id" to ToolParameter(
                type = "string",
                description = "ID проекта для выбора"
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val projectId = parameters["project_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'project_id' is required")

        return projectManager.switchProject(projectId).fold(
            onSuccess = { project ->
                ToolResult.Success(
                    output = "Switched to project: ${project.name}\nPath: ${project.rootPath}\nType: ${project.type}",
                    metadata = mapOf(
                        "projectId" to project.id,
                        "projectName" to project.name,
                        "projectType" to project.type.name
                    )
                )
            },
            onFailure = { e ->
                ToolResult.Error("Failed to switch project: ${e.message}")
            }
        )
    }
}

/**
 * Инструмент для получения списка проектов.
 */
class ListProjectsTool(private val projectManager: ProjectManager) : Tool {
    override val name = "list_projects"

    override val description = """
        Показывает список всех зарегистрированных проектов.
        Текущий активный проект помечен звёздочкой.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val projects = projectManager.getAllProjects()
        val currentProject = projectManager.getCurrentProject()

        if (projects.isEmpty()) {
            return ToolResult.Success(
                output = "No projects registered. Use 'register_project' to add a project.",
                metadata = mapOf("count" to "0")
            )
        }

        val output = buildString {
            appendLine("Registered projects:")
            appendLine()
            projects.forEach { project ->
                val marker = if (project.id == currentProject?.id) "* " else "  "
                appendLine("$marker${project.id}")
                appendLine("    Name: ${project.name}")
                appendLine("    Path: ${project.rootPath}")
                appendLine("    Type: ${project.type}")
                project.description?.let { appendLine("    Description: $it") }

                // Показываем git ветку если есть
                projectManager.getGitBranch(project.id)?.let { branch ->
                    appendLine("    Git branch: $branch")
                }
                appendLine()
            }
        }

        return ToolResult.Success(
            output = output,
            metadata = mapOf(
                "count" to projects.size.toString(),
                "currentProject" to (currentProject?.id ?: "none")
            )
        )
    }
}

/**
 * Инструмент для регистрации нового проекта.
 */
class RegisterProjectTool(private val projectManager: ProjectManager) : Tool {
    override val name = "register_project"

    override val description = """
        Регистрирует новый проект для работы.
        Тип проекта определяется автоматически.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(
                type = "string",
                description = "Абсолютный путь к корню проекта"
            ),
            "name" to ToolParameter(
                type = "string",
                description = "Название проекта (опционально, по умолчанию - имя папки)"
            ),
            "id" to ToolParameter(
                type = "string",
                description = "ID проекта (опционально, по умолчанию - имя папки в нижнем регистре)"
            ),
            "description" to ToolParameter(
                type = "string",
                description = "Описание проекта (опционально)"
            )
        ),
        required = listOf("path")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = parameters["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'path' is required")

        val name = parameters["name"]?.jsonPrimitive?.content
        val id = parameters["id"]?.jsonPrimitive?.content
        val description = parameters["description"]?.jsonPrimitive?.content

        // Создаём проект с автоопределением
        val autoProject = ProjectManager.createProjectFromPath(path, name)

        val project = autoProject.copy(
            id = id ?: autoProject.id,
            description = description
        )

        return projectManager.registerProject(project).fold(
            onSuccess = {
                ToolResult.Success(
                    output = buildString {
                        appendLine("Project registered successfully!")
                        appendLine()
                        appendLine("ID: ${project.id}")
                        appendLine("Name: ${project.name}")
                        appendLine("Path: ${project.rootPath}")
                        appendLine("Type: ${project.type} (auto-detected)")
                        project.readmePath?.let { appendLine("README: $it") }
                        project.docsPath?.let { appendLine("Docs folder: $it") }
                    },
                    metadata = mapOf(
                        "projectId" to project.id,
                        "projectType" to project.type.name
                    )
                )
            },
            onFailure = { e ->
                ToolResult.Error("Failed to register project: ${e.message}")
            }
        )
    }
}

/**
 * Инструмент для удаления проекта из реестра.
 */
class UnregisterProjectTool(private val projectManager: ProjectManager) : Tool {
    override val name = "unregister_project"

    override val description = """
        Удаляет проект из реестра (не удаляет файлы проекта!).
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = mapOf(
            "project_id" to ToolParameter(
                type = "string",
                description = "ID проекта для удаления"
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val projectId = parameters["project_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Parameter 'project_id' is required")

        return if (projectManager.unregisterProject(projectId)) {
            ToolResult.Success(
                output = "Project '$projectId' unregistered successfully",
                metadata = mapOf("projectId" to projectId)
            )
        } else {
            ToolResult.Error("Project '$projectId' not found")
        }
    }
}

/**
 * Инструмент для получения информации о текущем проекте.
 */
class ProjectInfoTool(private val projectManager: ProjectManager) : Tool {
    override val name = "project_info"

    override val description = """
        Показывает подробную информацию о текущем проекте:
        структуру папок, git ветку, README и т.д.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val project = projectManager.getCurrentProject()
            ?: return ToolResult.Error("No project selected. Use 'select_project' or 'list_projects' first.")

        val output = buildString {
            appendLine("=== Project Information ===")
            appendLine()
            appendLine("ID: ${project.id}")
            appendLine("Name: ${project.name}")
            appendLine("Path: ${project.rootPath}")
            appendLine("Type: ${project.type}")
            project.description?.let { appendLine("Description: $it") }
            appendLine()

            // Git информация
            projectManager.getGitBranch()?.let { branch ->
                appendLine("Git branch: $branch")
            }
            appendLine()

            // README
            appendLine("README: ${project.readmePath ?: "not found"}")
            appendLine("Docs folder: ${project.docsPath ?: "not found"}")
            appendLine()

            // Docs файлы
            val docsFiles = projectManager.getDocsFiles()
            if (docsFiles.isNotEmpty()) {
                appendLine("Documentation files:")
                docsFiles.take(10).forEach { appendLine("  - $it") }
                if (docsFiles.size > 10) {
                    appendLine("  ... and ${docsFiles.size - 10} more")
                }
            }
        }

        return ToolResult.Success(
            output = output,
            metadata = mapOf(
                "projectId" to project.id,
                "projectType" to project.type.name,
                "gitBranch" to (projectManager.getGitBranch() ?: "none")
            )
        )
    }
}

/**
 * Инструмент для чтения README проекта.
 */
class ReadProjectReadmeTool(private val projectManager: ProjectManager) : Tool {
    override val name = "read_project_readme"

    override val description = """
        Читает содержимое README файла текущего проекта.
        Полезно для понимания структуры и правил проекта.
    """.trimIndent()

    override val parametersSchema = ToolParametersSchema(
        properties = emptyMap(),
        required = emptyList()
    )

    override suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult {
        val content = projectManager.getReadmeContent()
            ?: return ToolResult.Error("README not found in current project")

        return ToolResult.Success(
            output = content,
            metadata = mapOf("hasReadme" to "true")
        )
    }
}
