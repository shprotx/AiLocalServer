package kz.shprot.assistant

import kotlinx.serialization.json.*
import kz.shprot.RAGManager
import kz.shprot.support.*
import kz.shprot.tools.ProjectManager
import org.slf4j.LoggerFactory

/**
 * Реестр внутренних инструментов для TeamAssistant.
 * Включает инструменты для работы с задачами, проектами и аналитикой.
 */
class InternalToolsRegistry(
    private val ticketManager: TicketManager,
    private val projectManager: ProjectManager,
    private val ragManager: RAGManager? = null
) {
    private val logger = LoggerFactory.getLogger(InternalToolsRegistry::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Список внутренних инструментов
    private val internalToolNames = setOf(
        "task_list",
        "task_create",
        "task_update",
        "task_get",
        "task_search",
        "task_analyze",
        "task_stats",
        "project_status",
        "project_health",
        "github_sync",
        "github_issues_list",
        "github_issue_create"
    )

    /**
     * Проверяет, является ли инструмент внутренним.
     */
    fun hasInternalTool(toolName: String): Boolean = toolName in internalToolNames

    /**
     * Выполняет внутренний инструмент.
     */
    suspend fun executeTool(
        toolName: String,
        params: Map<String, Any>,
        projectId: String
    ): String {
        logger.info("🔧 Выполнение внутреннего инструмента: $toolName")

        return when (toolName) {
            "task_list" -> executeTaskList(params, projectId)
            "task_create" -> executeTaskCreate(params, projectId)
            "task_update" -> executeTaskUpdate(params)
            "task_get" -> executeTaskGet(params)
            "task_search" -> executeTaskSearch(params, projectId)
            "task_analyze" -> executeTaskAnalyze(params, projectId)
            "task_stats" -> executeTaskStats(projectId)
            "project_status" -> executeProjectStatus(projectId)
            "project_health" -> executeProjectHealth(projectId)
            "github_sync" -> executeGitHubSync(params, projectId)
            "github_issues_list" -> executeGitHubIssuesList(params)
            "github_issue_create" -> executeGitHubIssueCreate(params)
            else -> "ERROR: Unknown internal tool: $toolName"
        }
    }

    /**
     * Возвращает описание всех внутренних инструментов.
     */
    fun getToolsDescription(): String = """
- task_list: Получить список задач с фильтрацией
  Параметры: priority (LOW/MEDIUM/HIGH/CRITICAL), status (OPEN/IN_PROGRESS/WAITING/RESOLVED/CLOSED), category (AUTH/TASKS/SCANNER/PRINTER/MAP/SYNC/PERFORMANCE/UI/OTHER), limit (число)

- task_create: Создать новую задачу
  Параметры: title (обязательно), description, priority (LOW/MEDIUM/HIGH/CRITICAL), category (AUTH/TASKS/SCANNER/PRINTER/MAP/SYNC/PERFORMANCE/UI/OTHER)

- task_update: Обновить существующую задачу
  Параметры: taskId (обязательно), status, priority, assignee

- task_get: Получить детали задачи по ID
  Параметры: taskId (обязательно)

- task_search: Поиск задач по тексту
  Параметры: query (обязательно)

- task_analyze: Анализ задач с рекомендациями по приоритетам
  Параметры: нет (использует RAG для контекста проекта)

- task_stats: Статистика по задачам проекта
  Параметры: нет

- project_status: Общий статус проекта (открытые задачи, блокеры, прогресс)
  Параметры: нет

- project_health: "Здоровье" проекта (техдолг, критичные баги)
  Параметры: нет

- github_sync: Синхронизация задач с GitHub Issues (двусторонняя)
  Параметры: owner (обязательно), repo (обязательно)

- github_issues_list: Получить список issues из GitHub
  Параметры: owner (обязательно), repo (обязательно), state (open/closed/all)

- github_issue_create: Создать issue в GitHub
  Параметры: owner (обязательно), repo (обязательно), title (обязательно), body, labels (массив)
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════
    // TASK TOOLS
    // ═══════════════════════════════════════════════════════════════

    private fun executeTaskList(params: Map<String, Any>, projectId: String): String {
        val priority = params["priority"]?.toString()?.let {
            runCatching { TicketPriority.valueOf(it.uppercase()) }.getOrNull()
        }
        val status = params["status"]?.toString()?.let {
            runCatching { TicketStatus.valueOf(it.uppercase()) }.getOrNull()
        }
        val category = params["category"]?.toString()?.let {
            runCatching { TicketCategory.valueOf(it.uppercase()) }.getOrNull()
        }
        val limit = (params["limit"] as? Number)?.toInt() ?: 20

        var tickets = ticketManager.getTicketsByProject(projectId)

        // Применяем фильтры
        priority?.let { p -> tickets = tickets.filter { it.priority == p } }
        status?.let { s -> tickets = tickets.filter { it.status == s } }
        category?.let { c -> tickets = tickets.filter { it.category == c } }

        tickets = tickets.take(limit)

        if (tickets.isEmpty()) {
            return "Задач не найдено с указанными фильтрами"
        }

        return buildString {
            appendLine("📋 Найдено ${tickets.size} задач:")
            appendLine()
            tickets.forEach { ticket ->
                val priorityEmoji = when (ticket.priority) {
                    TicketPriority.CRITICAL -> "🔴"
                    TicketPriority.HIGH -> "🟠"
                    TicketPriority.MEDIUM -> "🟡"
                    TicketPriority.LOW -> "🟢"
                }
                val statusEmoji = when (ticket.status) {
                    TicketStatus.OPEN -> "📭"
                    TicketStatus.IN_PROGRESS -> "🔄"
                    TicketStatus.WAITING -> "⏳"
                    TicketStatus.RESOLVED -> "✅"
                    TicketStatus.CLOSED -> "📪"
                }
                appendLine("$priorityEmoji #${ticket.id} ${ticket.title}")
                appendLine("   $statusEmoji ${ticket.status} | ${ticket.category} | ${ticket.priority}")
                if (ticket.assignee != null) {
                    appendLine("   👤 Исполнитель: ${ticket.assignee}")
                }
                appendLine()
            }
        }
    }

    private fun executeTaskCreate(params: Map<String, Any>, projectId: String): String {
        val title = params["title"]?.toString()
            ?: return "ERROR: Параметр 'title' обязателен"

        val description = params["description"]?.toString() ?: title
        val priority = params["priority"]?.toString()?.let {
            runCatching { TicketPriority.valueOf(it.uppercase()) }.getOrNull()
        } ?: TicketPriority.MEDIUM
        val category = params["category"]?.toString()?.let {
            runCatching { TicketCategory.valueOf(it.uppercase()) }.getOrNull()
        } ?: TicketCategory.OTHER

        val ticket = ticketManager.createTicket(CreateTicketRequest(
            projectId = projectId,
            title = title,
            description = description,
            priority = priority,
            category = category
        ))

        return """
✅ Создана задача #${ticket.id}
   Заголовок: ${ticket.title}
   Приоритет: ${ticket.priority}
   Категория: ${ticket.category}
   Статус: ${ticket.status}
        """.trimIndent()
    }

    private fun executeTaskUpdate(params: Map<String, Any>): String {
        val taskId = (params["taskId"] as? Number)?.toInt()
            ?: params["taskId"]?.toString()?.toIntOrNull()
            ?: return "ERROR: Параметр 'taskId' обязателен"

        val status = params["status"]?.toString()?.let {
            runCatching { TicketStatus.valueOf(it.uppercase()) }.getOrNull()
        }
        val priority = params["priority"]?.toString()?.let {
            runCatching { TicketPriority.valueOf(it.uppercase()) }.getOrNull()
        }
        val assignee = params["assignee"]?.toString()

        val updated = ticketManager.updateTicket(taskId, UpdateTicketRequest(
            status = status,
            priority = priority,
            assignee = assignee
        )) ?: return "ERROR: Задача #$taskId не найдена"

        return """
✅ Задача #${updated.id} обновлена
   Статус: ${updated.status}
   Приоритет: ${updated.priority}
   Исполнитель: ${updated.assignee ?: "не назначен"}
        """.trimIndent()
    }

    private fun executeTaskGet(params: Map<String, Any>): String {
        val taskId = (params["taskId"] as? Number)?.toInt()
            ?: params["taskId"]?.toString()?.toIntOrNull()
            ?: return "ERROR: Параметр 'taskId' обязателен"

        val ticket = ticketManager.getTicket(taskId)
            ?: return "ERROR: Задача #$taskId не найдена"

        return buildString {
            appendLine("📋 Задача #${ticket.id}")
            appendLine("═══════════════════════════════")
            appendLine("Заголовок: ${ticket.title}")
            appendLine("Описание: ${ticket.description}")
            appendLine("Статус: ${ticket.status}")
            appendLine("Приоритет: ${ticket.priority}")
            appendLine("Категория: ${ticket.category}")
            appendLine("Исполнитель: ${ticket.assignee ?: "не назначен"}")
            appendLine("Создана: ${formatTimestamp(ticket.createdAt)}")
            appendLine("Обновлена: ${formatTimestamp(ticket.updatedAt)}")

            if (ticket.tags.isNotEmpty()) {
                appendLine("Теги: ${ticket.tags.joinToString(", ")}")
            }

            if (ticket.comments.isNotEmpty()) {
                appendLine()
                appendLine("💬 Комментарии (${ticket.comments.size}):")
                ticket.comments.takeLast(3).forEach { comment ->
                    val author = if (comment.isFromSupport) "[Поддержка]" else "[Пользователь]"
                    appendLine("  $author ${comment.author}: ${comment.content.take(100)}...")
                }
            }
        }
    }

    private fun executeTaskSearch(params: Map<String, Any>, projectId: String): String {
        val query = params["query"]?.toString()
            ?: return "ERROR: Параметр 'query' обязателен"

        val tickets = ticketManager.searchTickets(projectId, query)

        if (tickets.isEmpty()) {
            return "По запросу '$query' задач не найдено"
        }

        return buildString {
            appendLine("🔍 Найдено ${tickets.size} задач по запросу '$query':")
            appendLine()
            tickets.take(10).forEach { ticket ->
                appendLine("• #${ticket.id} ${ticket.title} [${ticket.status}]")
            }
        }
    }

    private suspend fun executeTaskAnalyze(params: Map<String, Any>, projectId: String): String {
        val openTickets = ticketManager.getOpenTickets(projectId)

        if (openTickets.isEmpty()) {
            return "Нет открытых задач для анализа"
        }

        // Получаем контекст проекта через RAG (если доступен)
        val projectContext = ragManager?.let { rag ->
            runCatching {
                val enrichment = rag.augmentPromptWithKnowledgeDetailed(
                    userQuery = "критичные модули архитектура зависимости приоритеты",
                    originalMessages = emptyList()
                )
                enrichment.ragContext?.take(500) ?: ""
            }.getOrNull()
        } ?: ""

        // Группируем по приоритету
        val critical = openTickets.filter { it.priority == TicketPriority.CRITICAL }
        val high = openTickets.filter { it.priority == TicketPriority.HIGH }
        val medium = openTickets.filter { it.priority == TicketPriority.MEDIUM }
        val low = openTickets.filter { it.priority == TicketPriority.LOW }

        // Анализируем "возраст" задач
        val now = System.currentTimeMillis()
        val staleTickets = openTickets.filter {
            (now - it.updatedAt) > 7 * 24 * 60 * 60 * 1000 // больше 7 дней без обновления
        }

        return buildString {
            appendLine("📊 АНАЛИЗ ЗАДАЧ ПРОЕКТА")
            appendLine("═══════════════════════════════════════")
            appendLine()

            appendLine("📈 РАСПРЕДЕЛЕНИЕ ПО ПРИОРИТЕТАМ:")
            appendLine("  🔴 CRITICAL: ${critical.size}")
            appendLine("  🟠 HIGH: ${high.size}")
            appendLine("  🟡 MEDIUM: ${medium.size}")
            appendLine("  🟢 LOW: ${low.size}")
            appendLine()

            appendLine("🎯 РЕКОМЕНДУЕМЫЙ ПОРЯДОК ВЫПОЛНЕНИЯ:")
            appendLine()

            var order = 1

            // Сначала критичные
            critical.forEach { ticket ->
                appendLine("${order++}. 🔴 #${ticket.id} ${ticket.title}")
                appendLine("   Причина: CRITICAL приоритет - требует немедленного внимания")
                appendLine()
            }

            // Затем HIGH
            high.forEach { ticket ->
                val stale = if (staleTickets.contains(ticket)) " ⚠️ (без движения ${getDaysAgo(ticket.updatedAt)} дней)" else ""
                appendLine("${order++}. 🟠 #${ticket.id} ${ticket.title}$stale")
                appendLine("   Причина: HIGH приоритет${if (stale.isNotEmpty()) ", требует внимания" else ""}")
                appendLine()
            }

            // Затем MEDIUM
            medium.take(5).forEach { ticket ->
                appendLine("${order++}. 🟡 #${ticket.id} ${ticket.title}")
                appendLine("   Причина: MEDIUM приоритет")
                appendLine()
            }

            if (staleTickets.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ ЗАДАЧИ БЕЗ ДВИЖЕНИЯ (>7 дней):")
                staleTickets.take(5).forEach { ticket ->
                    appendLine("  • #${ticket.id} ${ticket.title} - ${getDaysAgo(ticket.updatedAt)} дней")
                }
            }

            if (projectContext.isNotEmpty()) {
                appendLine()
                appendLine("📚 КОНТЕКСТ ПРОЕКТА (из документации):")
                appendLine(projectContext.take(500))
            }

            appendLine()
            appendLine("💡 РЕКОМЕНДАЦИИ:")
            if (critical.isNotEmpty()) {
                appendLine("  • Начните с критичных задач - они блокируют работу")
            }
            if (staleTickets.size > 3) {
                appendLine("  • Много задач без движения - проверьте блокеры")
            }
            if (high.size > 5) {
                appendLine("  • Много HIGH задач - возможно стоит пересмотреть приоритеты")
            }
        }
    }

    private fun executeTaskStats(projectId: String): String {
        val stats = ticketManager.getStats(projectId)

        return buildString {
            appendLine("📊 СТАТИСТИКА ЗАДАЧ")
            appendLine("═══════════════════════════════")
            appendLine()
            appendLine("Всего: ${stats.total}")
            appendLine()
            appendLine("По статусам:")
            appendLine("  📭 Открыто: ${stats.open}")
            appendLine("  🔄 В работе: ${stats.inProgress}")
            appendLine("  ⏳ Ожидание: ${stats.waiting}")
            appendLine("  ✅ Решено: ${stats.resolved}")
            appendLine("  📪 Закрыто: ${stats.closed}")
            appendLine()
            appendLine("По приоритетам:")
            stats.byPriority.forEach { (priority, count) ->
                val emoji = when (priority) {
                    TicketPriority.CRITICAL -> "🔴"
                    TicketPriority.HIGH -> "🟠"
                    TicketPriority.MEDIUM -> "🟡"
                    TicketPriority.LOW -> "🟢"
                }
                appendLine("  $emoji $priority: $count")
            }
            appendLine()
            appendLine("По категориям:")
            stats.byCategory.forEach { (category, count) ->
                appendLine("  • $category: $count")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROJECT TOOLS
    // ═══════════════════════════════════════════════════════════════

    private fun executeProjectStatus(projectId: String): String {
        val project = projectManager.getProject(projectId)
        val stats = ticketManager.getStats(projectId)
        val openTickets = ticketManager.getOpenTickets(projectId)
        val criticalTickets = openTickets.filter { it.priority == TicketPriority.CRITICAL }

        return buildString {
            appendLine("📊 СТАТУС ПРОЕКТА")
            appendLine("═══════════════════════════════════════")
            appendLine()

            if (project != null) {
                appendLine("📁 ${project.name}")
                appendLine("   Тип: ${project.type}")
                appendLine("   Путь: ${project.rootPath}")
                appendLine()
            }

            appendLine("📋 ЗАДАЧИ:")
            appendLine("   Открыто: ${stats.open}")
            appendLine("   В работе: ${stats.inProgress}")
            appendLine("   Ожидание: ${stats.waiting}")
            appendLine("   Закрыто за всё время: ${stats.closed + stats.resolved}")
            appendLine()

            if (criticalTickets.isNotEmpty()) {
                appendLine("🚨 КРИТИЧНЫЕ ЗАДАЧИ (${criticalTickets.size}):")
                criticalTickets.take(5).forEach { ticket ->
                    appendLine("   🔴 #${ticket.id} ${ticket.title}")
                }
                appendLine()
            }

            val healthScore = calculateHealthScore(stats, openTickets)
            appendLine("📈 Здоровье проекта: $healthScore/10")
        }
    }

    private fun executeProjectHealth(projectId: String): String {
        val stats = ticketManager.getStats(projectId)
        val openTickets = ticketManager.getOpenTickets(projectId)
        val now = System.currentTimeMillis()

        // Метрики здоровья
        val criticalCount = openTickets.count { it.priority == TicketPriority.CRITICAL }
        val highCount = openTickets.count { it.priority == TicketPriority.HIGH }
        val staleCount = openTickets.count { (now - it.updatedAt) > 7 * 24 * 60 * 60 * 1000 }
        val overdueCount = openTickets.count { (now - it.createdAt) > 30 * 24 * 60 * 60 * 1000 }

        val healthScore = calculateHealthScore(stats, openTickets)

        return buildString {
            appendLine("🏥 ЗДОРОВЬЕ ПРОЕКТА")
            appendLine("═══════════════════════════════════════")
            appendLine()

            appendLine("📊 ОБЩАЯ ОЦЕНКА: $healthScore/10")
            appendLine()

            appendLine("📋 МЕТРИКИ:")
            appendLine("   🔴 Критичные задачи: $criticalCount")
            appendLine("   🟠 Высокий приоритет: $highCount")
            appendLine("   ⏰ Задачи без движения (>7 дней): $staleCount")
            appendLine("   📅 Старые задачи (>30 дней): $overdueCount")
            appendLine()

            appendLine("💡 РЕКОМЕНДАЦИИ:")
            if (criticalCount > 0) {
                appendLine("   ❗ Есть критичные задачи - требуют немедленного внимания")
            }
            if (staleCount > 3) {
                appendLine("   ⚠️ Много задач без движения - проверьте блокеры")
            }
            if (overdueCount > 5) {
                appendLine("   📌 Накопился техдолг - запланируйте спринт на закрытие")
            }
            if (healthScore >= 8) {
                appendLine("   ✅ Проект в хорошем состоянии!")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GITHUB TOOLS (заглушки - будут реализованы через MCP)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun executeGitHubSync(params: Map<String, Any>, projectId: String): String {
        val owner = params["owner"]?.toString()
            ?: return "ERROR: Параметр 'owner' обязателен"
        val repo = params["repo"]?.toString()
            ?: return "ERROR: Параметр 'repo' обязателен"

        // TODO: Реализовать через MCP github tools
        return """
🔄 Синхронизация с GitHub $owner/$repo
═══════════════════════════════════════

⚠️ Функция синхронизации находится в разработке.

Для работы с GitHub Issues используйте:
- github_issues_list - получить список issues
- github_issue_create - создать issue

Ручная синхронизация пока недоступна.
        """.trimIndent()
    }

    private suspend fun executeGitHubIssuesList(params: Map<String, Any>): String {
        val owner = params["owner"]?.toString()
            ?: return "ERROR: Параметр 'owner' обязателен"
        val repo = params["repo"]?.toString()
            ?: return "ERROR: Параметр 'repo' обязателен"
        val state = params["state"]?.toString() ?: "open"

        // Этот инструмент будет перенаправлен на MCP github tools
        return "REDIRECT_TO_MCP:mcp__github__list_issues:owner=$owner,repo=$repo,state=$state"
    }

    private suspend fun executeGitHubIssueCreate(params: Map<String, Any>): String {
        val owner = params["owner"]?.toString()
            ?: return "ERROR: Параметр 'owner' обязателен"
        val repo = params["repo"]?.toString()
            ?: return "ERROR: Параметр 'repo' обязателен"
        val title = params["title"]?.toString()
            ?: return "ERROR: Параметр 'title' обязателен"
        val body = params["body"]?.toString() ?: ""

        // Этот инструмент будет перенаправлен на MCP github tools
        return "REDIRECT_TO_MCP:mcp__github__issue_write:owner=$owner,repo=$repo,title=$title,body=$body"
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun calculateHealthScore(stats: TicketStats, openTickets: List<Ticket>): Int {
        var score = 10

        // Снижаем за критичные задачи
        val criticalCount = openTickets.count { it.priority == TicketPriority.CRITICAL }
        score -= minOf(criticalCount * 2, 4)

        // Снижаем за много открытых HIGH
        val highCount = openTickets.count { it.priority == TicketPriority.HIGH }
        if (highCount > 5) score -= 1
        if (highCount > 10) score -= 1

        // Снижаем за задачи без движения
        val now = System.currentTimeMillis()
        val staleCount = openTickets.count { (now - it.updatedAt) > 7 * 24 * 60 * 60 * 1000 }
        if (staleCount > 3) score -= 1
        if (staleCount > 7) score -= 1

        // Снижаем за старые задачи
        val overdueCount = openTickets.count { (now - it.createdAt) > 30 * 24 * 60 * 60 * 1000 }
        if (overdueCount > 5) score -= 1

        return maxOf(score, 1)
    }

    private fun formatTimestamp(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        return format.format(date)
    }

    private fun getDaysAgo(timestamp: Long): Int {
        val now = System.currentTimeMillis()
        return ((now - timestamp) / (24 * 60 * 60 * 1000)).toInt()
    }
}
