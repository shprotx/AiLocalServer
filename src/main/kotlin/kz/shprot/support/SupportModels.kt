package kz.shprot.support

import kotlinx.serialization.Serializable

/**
 * Статус тикета поддержки.
 */
@Serializable
enum class TicketStatus {
    OPEN,       // Открыт, ожидает ответа
    IN_PROGRESS, // В работе
    WAITING,    // Ожидает ответа пользователя
    RESOLVED,   // Решён
    CLOSED      // Закрыт
}

/**
 * Приоритет тикета.
 */
@Serializable
enum class TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Категория тикета (связана с модулями приложения).
 */
@Serializable
enum class TicketCategory {
    AUTH,           // Авторизация
    TASKS,          // Задачи
    SCANNER,        // Сканер штрихкодов
    PRINTER,        // Печать
    MAP,            // Карты
    SYNC,           // Синхронизация данных
    PERFORMANCE,    // Производительность
    UI,             // Интерфейс
    OTHER           // Прочее
}

/**
 * Тикет поддержки.
 */
@Serializable
data class Ticket(
    val id: Int,
    val projectId: String,
    val title: String,
    val description: String,
    val status: TicketStatus = TicketStatus.OPEN,
    val priority: TicketPriority = TicketPriority.MEDIUM,
    val category: TicketCategory = TicketCategory.OTHER,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val userId: String? = null,
    val assignee: String? = null,
    val tags: List<String> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val comments: List<TicketComment> = emptyList()
)

/**
 * Комментарий к тикету.
 */
@Serializable
data class TicketComment(
    val id: Int,
    val ticketId: Int,
    val author: String,
    val content: String,
    val isFromSupport: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * FAQ запись.
 */
@Serializable
data class FaqEntry(
    val id: Int,
    val projectId: String,
    val question: String,
    val answer: String,
    val category: TicketCategory = TicketCategory.OTHER,
    val keywords: List<String> = emptyList(),
    val relatedTicketIds: List<Int> = emptyList(),
    val helpfulCount: Int = 0,
    val notHelpfulCount: Int = 0
)

/**
 * Запрос в поддержку.
 */
@Serializable
data class SupportRequest(
    val projectId: String,
    val question: String,
    val ticketId: Int? = null,
    val includeTickets: Boolean = true,
    val includeFaq: Boolean = true,
    val includeDocs: Boolean = true
)

/**
 * Ответ от системы поддержки.
 */
@Serializable
data class SupportResponse(
    val answer: String,
    val relatedTickets: List<TicketSummary> = emptyList(),
    val relatedFaqs: List<FaqSummary> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val suggestedCategory: TicketCategory? = null,
    val confidence: Double = 0.0
)

/**
 * Краткая информация о тикете для ответа.
 */
@Serializable
data class TicketSummary(
    val id: Int,
    val title: String,
    val status: TicketStatus,
    val category: TicketCategory
)

/**
 * Краткая информация о FAQ для ответа.
 */
@Serializable
data class FaqSummary(
    val id: Int,
    val question: String,
    val category: TicketCategory
)

/**
 * Конфигурация хранилища тикетов и FAQ.
 */
@Serializable
data class SupportDataConfig(
    val tickets: List<Ticket> = emptyList(),
    val nextTicketId: Int = 1
)

/**
 * Конфигурация FAQ.
 */
@Serializable
data class FaqDataConfig(
    val faqs: List<FaqEntry> = emptyList(),
    val nextFaqId: Int = 1
)

/**
 * Запрос на создание тикета.
 */
@Serializable
data class CreateTicketRequest(
    val projectId: String,
    val title: String,
    val description: String,
    val category: TicketCategory = TicketCategory.OTHER,
    val priority: TicketPriority = TicketPriority.MEDIUM,
    val userId: String? = null
)

/**
 * Запрос на обновление тикета.
 */
@Serializable
data class UpdateTicketRequest(
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val assignee: String? = null,
    val tags: List<String>? = null
)

/**
 * Запрос на добавление комментария.
 */
@Serializable
data class AddCommentRequest(
    val author: String,
    val content: String,
    val isFromSupport: Boolean = false
)
