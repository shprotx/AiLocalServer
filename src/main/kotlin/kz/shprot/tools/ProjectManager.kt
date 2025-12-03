package kz.shprot.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер проектов.
 * Позволяет регистрировать и переключаться между внешними проектами.
 * Хранит информацию о проектах и предоставляет контекст для инструментов.
 */
class ProjectManager(
    private val configPath: String = "projects.json"
) {
    private val projects = ConcurrentHashMap<String, Project>()
    private var currentProjectId: String? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        loadProjects()
    }

    /**
     * Информация о проекте.
     */
    @Serializable
    data class Project(
        val id: String,
        val name: String,
        val rootPath: String,
        val type: ProjectType = ProjectType.GENERIC,
        val description: String? = null,
        val docsPath: String? = null,  // Путь к папке docs относительно rootPath
        val readmePath: String? = null  // Путь к README относительно rootPath
    )

    @Serializable
    enum class ProjectType {
        ANDROID,    // Android проект (Kotlin/Java)
        KOTLIN,     // Kotlin проект
        JAVA,       // Java проект
        TYPESCRIPT, // TypeScript/JavaScript
        PYTHON,     // Python проект
        GENERIC     // Любой другой тип
    }

    /**
     * Регистрирует новый проект.
     */
    fun registerProject(project: Project): Result<Unit> {
        val projectDir = File(project.rootPath)

        if (!projectDir.exists()) {
            return Result.failure(IllegalArgumentException("Project directory does not exist: ${project.rootPath}"))
        }

        if (!projectDir.isDirectory) {
            return Result.failure(IllegalArgumentException("Path is not a directory: ${project.rootPath}"))
        }

        projects[project.id] = project
        saveProjects()
        println("[ProjectManager] Registered project: ${project.name} (${project.id}) at ${project.rootPath}")
        return Result.success(Unit)
    }

    /**
     * Удаляет проект из реестра.
     */
    fun unregisterProject(projectId: String): Boolean {
        val removed = projects.remove(projectId) != null
        if (removed) {
            if (currentProjectId == projectId) {
                currentProjectId = null
            }
            saveProjects()
            println("[ProjectManager] Unregistered project: $projectId")
        }
        return removed
    }

    /**
     * Переключается на указанный проект.
     */
    fun switchProject(projectId: String): Result<Project> {
        val project = projects[projectId]
            ?: return Result.failure(IllegalArgumentException("Project not found: $projectId"))

        currentProjectId = projectId
        println("[ProjectManager] Switched to project: ${project.name}")
        return Result.success(project)
    }

    /**
     * Возвращает текущий активный проект.
     */
    fun getCurrentProject(): Project? {
        return currentProjectId?.let { projects[it] }
    }

    /**
     * Возвращает проект по ID.
     */
    fun getProject(projectId: String): Project? = projects[projectId]

    /**
     * Возвращает все зарегистрированные проекты.
     */
    fun getAllProjects(): List<Project> = projects.values.toList()

    /**
     * Создаёт контекст выполнения для инструментов на основе текущего проекта.
     */
    fun createExecutionContext(): ToolExecutionContext {
        val project = getCurrentProject()
        return ToolExecutionContext(
            projectRoot = project?.rootPath,
            projectName = project?.name,
            workingDirectory = project?.rootPath ?: System.getProperty("user.dir"),
            metadata = buildMap {
                project?.let {
                    put("projectType", it.type.name)
                    put("projectId", it.id)
                    it.docsPath?.let { docs -> put("docsPath", docs) }
                    it.readmePath?.let { readme -> put("readmePath", readme) }
                }
            }
        )
    }

    /**
     * Автоопределение типа проекта по содержимому директории.
     */
    fun detectProjectType(rootPath: String): ProjectType {
        val dir = File(rootPath)
        if (!dir.exists() || !dir.isDirectory) return ProjectType.GENERIC

        val files = dir.listFiles()?.map { it.name } ?: emptyList()

        return when {
            // Android проект
            "build.gradle" in files && "app" in files -> ProjectType.ANDROID
            "build.gradle.kts" in files && "app" in files -> ProjectType.ANDROID

            // Kotlin/Gradle проект
            "build.gradle.kts" in files -> ProjectType.KOTLIN
            "build.gradle" in files -> ProjectType.KOTLIN

            // Java Maven проект
            "pom.xml" in files -> ProjectType.JAVA

            // TypeScript/JavaScript
            "package.json" in files -> ProjectType.TYPESCRIPT

            // Python
            "setup.py" in files || "requirements.txt" in files || "pyproject.toml" in files -> ProjectType.PYTHON

            else -> ProjectType.GENERIC
        }
    }

    /**
     * Находит README файл в проекте.
     */
    fun findReadme(rootPath: String): String? {
        val dir = File(rootPath)
        val readmeNames = listOf("README.md", "README.txt", "README", "readme.md", "Readme.md")

        return readmeNames
            .map { File(dir, it) }
            .find { it.exists() && it.isFile }
            ?.relativeTo(dir)
            ?.path
    }

    /**
     * Находит папку docs в проекте.
     */
    fun findDocsFolder(rootPath: String): String? {
        val dir = File(rootPath)
        val docsNames = listOf("docs", "doc", "documentation", ".claude")

        return docsNames
            .map { File(dir, it) }
            .find { it.exists() && it.isDirectory }
            ?.relativeTo(dir)
            ?.path
    }

    /**
     * Получает содержимое README файла текущего проекта.
     */
    fun getReadmeContent(): String? {
        val project = getCurrentProject() ?: return null
        val readmePath = project.readmePath ?: findReadme(project.rootPath) ?: return null

        val readmeFile = File(project.rootPath, readmePath)
        return if (readmeFile.exists()) readmeFile.readText() else null
    }

    /**
     * Получает список файлов документации текущего проекта.
     */
    fun getDocsFiles(): List<String> {
        val project = getCurrentProject() ?: return emptyList()
        val docsPath = project.docsPath ?: findDocsFolder(project.rootPath) ?: return emptyList()

        val docsDir = File(project.rootPath, docsPath)
        if (!docsDir.exists() || !docsDir.isDirectory) return emptyList()

        return docsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("md", "txt", "rst", "adoc") }
            .map { it.relativeTo(File(project.rootPath)).path }
            .toList()
    }

    /**
     * Получает текущую ветку git для проекта.
     */
    fun getGitBranch(projectId: String? = null): String? {
        val project = projectId?.let { projects[it] } ?: getCurrentProject() ?: return null

        return runCatching {
            val headFile = File(project.rootPath, ".git/HEAD")
            if (headFile.exists()) {
                val content = headFile.readText().trim()
                if (content.startsWith("ref: refs/heads/")) {
                    content.removePrefix("ref: refs/heads/")
                } else {
                    content.take(7) // short SHA for detached HEAD
                }
            } else null
        }.getOrNull()
    }

    /**
     * Проверяет, является ли путь безопасным (не выходит за пределы проекта).
     */
    fun isPathSafe(path: String, projectId: String? = null): Boolean {
        val project = projectId?.let { projects[it] } ?: getCurrentProject() ?: return false

        val projectRoot = File(project.rootPath).canonicalPath
        val targetPath = File(project.rootPath, path).canonicalPath

        return targetPath.startsWith(projectRoot)
    }

    private fun loadProjects() {
        runCatching {
            val configFile = File(configPath)
            if (configFile.exists()) {
                val config = json.decodeFromString<ProjectConfig>(configFile.readText())
                projects.clear()
                config.projects.forEach { projects[it.id] = it }
                currentProjectId = config.currentProjectId
                println("[ProjectManager] Loaded ${projects.size} projects from config")
            }
        }.onFailure {
            println("[ProjectManager] Failed to load projects config: ${it.message}")
        }
    }

    private fun saveProjects() {
        runCatching {
            val config = ProjectConfig(
                projects = projects.values.toList(),
                currentProjectId = currentProjectId
            )
            File(configPath).writeText(json.encodeToString(ProjectConfig.serializer(), config))
            println("[ProjectManager] Saved projects config")
        }.onFailure {
            println("[ProjectManager] Failed to save projects config: ${it.message}")
        }
    }

    @Serializable
    private data class ProjectConfig(
        val projects: List<Project>,
        val currentProjectId: String? = null
    )

    companion object {
        /**
         * Создаёт проект с автоматическим определением параметров.
         */
        fun createProjectFromPath(rootPath: String, name: String? = null): Project {
            val manager = ProjectManager()
            val dir = File(rootPath)

            return Project(
                id = dir.name.lowercase().replace(Regex("[^a-z0-9]"), "-"),
                name = name ?: dir.name,
                rootPath = dir.canonicalPath,
                type = manager.detectProjectType(rootPath),
                readmePath = manager.findReadme(rootPath),
                docsPath = manager.findDocsFolder(rootPath)
            )
        }
    }
}
