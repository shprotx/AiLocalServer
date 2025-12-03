package kz.shprot.tools

import kz.shprot.tools.impl.*

/**
 * Фабрика для создания и регистрации всех инструментов.
 */
object ToolFactory {

    /**
     * Создаёт ToolRegistry с полным набором инструментов.
     */
    fun createRegistry(projectManager: ProjectManager): ToolRegistry {
        val registry = ToolRegistry()

        // Файловые инструменты
        registry.register(ReadFileTool(), "file")
        registry.register(WriteFileTool(), "file")
        registry.register(EditFileTool(), "file")
        registry.register(FindFileTool(), "file")      // Поиск по имени файла
        registry.register(SearchFilesTool(), "file")   // Поиск по glob паттерну
        registry.register(GrepTool(), "file")
        registry.register(ListDirectoryTool(), "file")

        // Git инструменты
        registry.register(GitBranchTool(), "git")
        registry.register(GitStatusTool(), "git")
        registry.register(GitDiffTool(), "git")
        registry.register(GitLogTool(), "git")

        // Инструменты управления проектами
        registry.register(SelectProjectTool(projectManager), "project")
        registry.register(ListProjectsTool(projectManager), "project")
        registry.register(RegisterProjectTool(projectManager), "project")
        registry.register(UnregisterProjectTool(projectManager), "project")
        registry.register(ProjectInfoTool(projectManager), "project")
        registry.register(ReadProjectReadmeTool(projectManager), "project")

        println("[ToolFactory] Created registry with ${registry.count()} tools in ${registry.getCategories().size} categories")
        return registry
    }

    /**
     * Создаёт минимальный набор инструментов (для тестирования).
     */
    fun createMinimalRegistry(projectManager: ProjectManager): ToolRegistry {
        val registry = ToolRegistry()

        // Только базовые файловые и git
        registry.register(ReadFileTool(), "file")
        registry.register(GrepTool(), "file")
        registry.register(GitBranchTool(), "git")
        registry.register(ListProjectsTool(projectManager), "project")
        registry.register(SelectProjectTool(projectManager), "project")

        return registry
    }
}
