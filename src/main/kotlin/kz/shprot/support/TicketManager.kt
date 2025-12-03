package kz.shprot.support

import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер тикетов поддержки.
 * Управляет CRUD операциями над тикетами и хранит их в JSON файле.
 */
class TicketManager(
    private val dataPath: String = "support_data/tickets.json"
) {
    private val tickets = ConcurrentHashMap<Int, Ticket>()
    private var nextId = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        ensureDataDirectory()
        loadTickets()
    }

    /**
     * Создаёт новый тикет.
     */
    fun createTicket(request: CreateTicketRequest): Ticket {
        val ticket = Ticket(
            id = nextId++,
            projectId = request.projectId,
            title = request.title,
            description = request.description,
            category = request.category,
            priority = request.priority,
            userId = request.userId
        )
        tickets[ticket.id] = ticket
        saveTickets()
        println("[TicketManager] Created ticket #${ticket.id}: ${ticket.title}")
        return ticket
    }

    /**
     * Получает тикет по ID.
     */
    fun getTicket(ticketId: Int): Ticket? = tickets[ticketId]

    /**
     * Получает все тикеты для проекта.
     */
    fun getTicketsByProject(projectId: String): List<Ticket> {
        return tickets.values
            .filter { it.projectId == projectId }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Получает открытые тикеты для проекта.
     */
    fun getOpenTickets(projectId: String): List<Ticket> {
        return getTicketsByProject(projectId)
            .filter { it.status in listOf(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING) }
    }

    /**
     * Получает тикеты по категории.
     */
    fun getTicketsByCategory(projectId: String, category: TicketCategory): List<Ticket> {
        return getTicketsByProject(projectId)
            .filter { it.category == category }
    }

    /**
     * Обновляет тикет.
     */
    fun updateTicket(ticketId: Int, request: UpdateTicketRequest): Ticket? {
        val ticket = tickets[ticketId] ?: return null

        val updated = ticket.copy(
            status = request.status ?: ticket.status,
            priority = request.priority ?: ticket.priority,
            assignee = request.assignee ?: ticket.assignee,
            tags = request.tags ?: ticket.tags,
            updatedAt = System.currentTimeMillis()
        )

        tickets[ticketId] = updated
        saveTickets()
        println("[TicketManager] Updated ticket #$ticketId")
        return updated
    }

    /**
     * Добавляет комментарий к тикету.
     */
    fun addComment(ticketId: Int, request: AddCommentRequest): Ticket? {
        val ticket = tickets[ticketId] ?: return null

        val comment = TicketComment(
            id = ticket.comments.size + 1,
            ticketId = ticketId,
            author = request.author,
            content = request.content,
            isFromSupport = request.isFromSupport
        )

        val updated = ticket.copy(
            comments = ticket.comments + comment,
            updatedAt = System.currentTimeMillis()
        )

        tickets[ticketId] = updated
        saveTickets()
        println("[TicketManager] Added comment to ticket #$ticketId")
        return updated
    }

    /**
     * Закрывает тикет.
     */
    fun closeTicket(ticketId: Int): Ticket? {
        return updateTicket(ticketId, UpdateTicketRequest(status = TicketStatus.CLOSED))
    }

    /**
     * Решает тикет.
     */
    fun resolveTicket(ticketId: Int): Ticket? {
        return updateTicket(ticketId, UpdateTicketRequest(status = TicketStatus.RESOLVED))
    }

    /**
     * Удаляет тикет.
     */
    fun deleteTicket(ticketId: Int): Boolean {
        val removed = tickets.remove(ticketId) != null
        if (removed) {
            saveTickets()
            println("[TicketManager] Deleted ticket #$ticketId")
        }
        return removed
    }

    /**
     * Поиск тикетов по тексту.
     */
    fun searchTickets(projectId: String, query: String): List<Ticket> {
        val lowerQuery = query.lowercase()
        return getTicketsByProject(projectId).filter { ticket ->
            ticket.title.lowercase().contains(lowerQuery) ||
                    ticket.description.lowercase().contains(lowerQuery) ||
                    ticket.tags.any { it.lowercase().contains(lowerQuery) } ||
                    ticket.comments.any { it.content.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * Получает статистику по тикетам проекта.
     */
    fun getStats(projectId: String): TicketStats {
        val projectTickets = getTicketsByProject(projectId)
        return TicketStats(
            total = projectTickets.size,
            open = projectTickets.count { it.status == TicketStatus.OPEN },
            inProgress = projectTickets.count { it.status == TicketStatus.IN_PROGRESS },
            waiting = projectTickets.count { it.status == TicketStatus.WAITING },
            resolved = projectTickets.count { it.status == TicketStatus.RESOLVED },
            closed = projectTickets.count { it.status == TicketStatus.CLOSED },
            byCategory = projectTickets.groupBy { it.category }.mapValues { it.value.size },
            byPriority = projectTickets.groupBy { it.priority }.mapValues { it.value.size }
        )
    }

    /**
     * Получает краткую информацию о тикетах для ответа.
     */
    fun getTicketSummaries(projectId: String, limit: Int = 5): List<TicketSummary> {
        return getOpenTickets(projectId)
            .take(limit)
            .map { TicketSummary(it.id, it.title, it.status, it.category) }
    }

    private fun ensureDataDirectory() {
        val dir = File(dataPath).parentFile
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
            println("[TicketManager] Created data directory: ${dir.path}")
        }
    }

    private fun loadTickets() {
        runCatching {
            val file = File(dataPath)
            if (file.exists()) {
                val config = json.decodeFromString<SupportDataConfig>(file.readText())
                tickets.clear()
                config.tickets.forEach { tickets[it.id] = it }
                nextId = config.nextTicketId
                println("[TicketManager] Loaded ${tickets.size} tickets")
            }
        }.onFailure {
            println("[TicketManager] Failed to load tickets: ${it.message}")
        }
    }

    private fun saveTickets() {
        runCatching {
            val config = SupportDataConfig(
                tickets = tickets.values.toList().sortedBy { it.id },
                nextTicketId = nextId
            )
            File(dataPath).writeText(json.encodeToString(SupportDataConfig.serializer(), config))
        }.onFailure {
            println("[TicketManager] Failed to save tickets: ${it.message}")
        }
    }
}

/**
 * Статистика по тикетам.
 */
data class TicketStats(
    val total: Int,
    val open: Int,
    val inProgress: Int,
    val waiting: Int,
    val resolved: Int,
    val closed: Int,
    val byCategory: Map<TicketCategory, Int>,
    val byPriority: Map<TicketPriority, Int>
)
