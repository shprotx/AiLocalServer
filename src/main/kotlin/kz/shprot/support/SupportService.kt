package kz.shprot.support

import kz.shprot.YandexLLMClient
import kz.shprot.models.Message
import kz.shprot.tools.ProjectManager
import java.io.File

/**
 * Сервис поддержки пользователей.
 * Объединяет RAG (документация проекта), FAQ и тикеты для ответов на вопросы.
 */
class SupportService(
    private val llmClient: YandexLLMClient,
    private val projectManager: ProjectManager,
    private val ticketManager: TicketManager,
    private val faqManager: FaqManager
) {
    /**
     * Обрабатывает запрос в поддержку.
     * Собирает контекст из документации, FAQ и тикетов, затем генерирует ответ.
     */
    suspend fun processRequest(request: SupportRequest): SupportResponse {
        val projectId = request.projectId
        val question = request.question

        // 1. Собираем контекст
        val context = buildContext(request)

        // 2. Определяем категорию вопроса
        val suggestedCategory = detectCategory(question)

        // 3. Ищем релевантные FAQ
        val relatedFaqs = if (request.includeFaq) {
            faqManager.searchFaqs(projectId, question, limit = 3)
        } else emptyList()

        // 4. Ищем релевантные тикеты
        val relatedTickets = if (request.includeTickets) {
            ticketManager.searchTickets(projectId, question).take(3)
        } else emptyList()

        // 5. Если есть конкретный тикет - добавляем его контекст
        val ticketContext = request.ticketId?.let { ticketId ->
            ticketManager.getTicket(ticketId)?.let { ticket ->
                buildTicketContext(ticket)
            }
        } ?: ""

        // 6. Генерируем ответ через LLM
        val answer = generateAnswer(
            question = question,
            context = context,
            ticketContext = ticketContext,
            relatedFaqs = relatedFaqs,
            relatedTickets = relatedTickets,
            suggestedCategory = suggestedCategory
        )

        // 7. Собираем связанные файлы
        val relatedFiles = extractRelatedFiles(answer, projectId)

        return SupportResponse(
            answer = answer,
            relatedTickets = relatedTickets.map {
                TicketSummary(it.id, it.title, it.status, it.category)
            },
            relatedFaqs = relatedFaqs.map {
                FaqSummary(it.id, it.question, it.category)
            },
            relatedFiles = relatedFiles,
            suggestedCategory = suggestedCategory,
            confidence = calculateConfidence(relatedFaqs, relatedTickets)
        )
    }

    /**
     * Строит контекст из документации проекта (RAG).
     */
    private fun buildContext(request: SupportRequest): String {
        if (!request.includeDocs) return ""

        val project = projectManager.getProject(request.projectId) ?: return ""

        val contextParts = mutableListOf<String>()

        // README
        project.readmePath?.let { readmePath ->
            val readmeFile = File(project.rootPath, readmePath)
            if (readmeFile.exists()) {
                val content = readmeFile.readText().take(4000)
                contextParts.add("=== README ===\n$content")
            }
        }

        // CLAUDE.md или другая документация
        project.docsPath?.let { docsPath ->
            val docsDir = File(project.rootPath, docsPath)
            if (docsDir.exists() && docsDir.isDirectory) {
                docsDir.listFiles()
                    ?.filter { it.isFile && it.extension in listOf("md", "txt") }
                    ?.take(3)
                    ?.forEach { docFile ->
                        val content = docFile.readText().take(3000)
                        contextParts.add("=== ${docFile.name} ===\n$content")
                    }
            } else if (docsDir.exists() && docsDir.isFile) {
                val content = docsDir.readText().take(4000)
                contextParts.add("=== ${docsDir.name} ===\n$content")
            }
        }

        return contextParts.joinToString("\n\n")
    }

    /**
     * Строит контекст для конкретного тикета.
     */
    private fun buildTicketContext(ticket: Ticket): String {
        return buildString {
            appendLine("=== Контекст тикета #${ticket.id} ===")
            appendLine("Заголовок: ${ticket.title}")
            appendLine("Статус: ${ticket.status}")
            appendLine("Категория: ${ticket.category}")
            appendLine("Приоритет: ${ticket.priority}")
            appendLine()
            appendLine("Описание:")
            appendLine(ticket.description)

            if (ticket.comments.isNotEmpty()) {
                appendLine()
                appendLine("Комментарии:")
                ticket.comments.forEach { comment ->
                    val author = if (comment.isFromSupport) "[Поддержка]" else "[Пользователь]"
                    appendLine("$author ${comment.author}: ${comment.content}")
                }
            }

            if (ticket.relatedFiles.isNotEmpty()) {
                appendLine()
                appendLine("Связанные файлы: ${ticket.relatedFiles.joinToString(", ")}")
            }
        }
    }

    /**
     * Определяет категорию вопроса по ключевым словам.
     */
    private fun detectCategory(question: String): TicketCategory {
        val lowerQuestion = question.lowercase()

        return when {
            // Авторизация
            lowerQuestion.containsAny("авториз", "логин", "пароль", "вход", "auth", "login", "выход", "logout") ->
                TicketCategory.AUTH

            // Сканер
            lowerQuestion.containsAny("сканер", "штрихкод", "qr", "barcode", "scan", "камера") ->
                TicketCategory.SCANNER

            // Принтер
            lowerQuestion.containsAny("принтер", "печат", "print", "этикетк", "label") ->
                TicketCategory.PRINTER

            // Карты
            lowerQuestion.containsAny("карт", "map", "геолок", "gps", "местоположен") ->
                TicketCategory.MAP

            // Задачи
            lowerQuestion.containsAny("задач", "task", "заявк", "закры", "выполн") ->
                TicketCategory.TASKS

            // Синхронизация
            lowerQuestion.containsAny("синхрон", "sync", "данн", "загруз", "обновлен", "appwrite") ->
                TicketCategory.SYNC

            // Производительность
            lowerQuestion.containsAny("тормоз", "медлен", "зависа", "performance", "быстр", "долго") ->
                TicketCategory.PERFORMANCE

            // UI
            lowerQuestion.containsAny("экран", "кнопк", "интерфейс", "ui", "отображ", "показыва") ->
                TicketCategory.UI

            else -> TicketCategory.OTHER
        }
    }

    /**
     * Генерирует ответ через LLM с учётом всего контекста.
     */
    private suspend fun generateAnswer(
        question: String,
        context: String,
        ticketContext: String,
        relatedFaqs: List<FaqEntry>,
        relatedTickets: List<Ticket>,
        suggestedCategory: TicketCategory
    ): String {
        val systemPrompt = buildString {
            appendLine("Ты — ассистент поддержки для приложения Avadesk (управление складскими задачами).")
            appendLine("Твоя задача — помогать пользователям решать проблемы и отвечать на вопросы.")
            appendLine()
            appendLine("Правила ответа:")
            appendLine("1. Отвечай кратко и по существу")
            appendLine("2. Если есть похожие тикеты или FAQ — упоминай их номера")
            appendLine("3. Если проблема требует технической поддержки — рекомендуй создать тикет")
            appendLine("4. Указывай конкретные файлы/модули если знаешь их")
            appendLine("5. Используй markdown для форматирования")
            appendLine()
            appendLine("Категория вопроса: $suggestedCategory")

            if (context.isNotBlank()) {
                appendLine()
                appendLine("=== ДОКУМЕНТАЦИЯ ПРОЕКТА ===")
                appendLine(context.take(6000))
            }

            if (ticketContext.isNotBlank()) {
                appendLine()
                appendLine(ticketContext)
            }

            if (relatedFaqs.isNotEmpty()) {
                appendLine()
                appendLine("=== ПОХОЖИЕ FAQ ===")
                relatedFaqs.forEach { faq ->
                    appendLine("FAQ #${faq.id}: ${faq.question}")
                    appendLine("Ответ: ${faq.answer.take(300)}...")
                    appendLine()
                }
            }

            if (relatedTickets.isNotEmpty()) {
                appendLine()
                appendLine("=== ПОХОЖИЕ ТИКЕТЫ ===")
                relatedTickets.forEach { ticket ->
                    appendLine("Тикет #${ticket.id} [${ticket.status}]: ${ticket.title}")
                    appendLine("Описание: ${ticket.description.take(200)}...")
                    appendLine()
                }
            }
        }

        val messages = listOf(
            Message(role = "system", text = systemPrompt),
            Message(role = "user", text = question)
        )

        return runCatching {
            llmClient.sendMessage(messages, temperature = 0.3, useJsonSchema = false)
        }.getOrElse { e ->
            println("[SupportService] LLM error: ${e.message}")
            "Извините, произошла ошибка при обработке запроса. Попробуйте позже или создайте тикет."
        }
    }

    /**
     * Извлекает упомянутые файлы из ответа.
     */
    private fun extractRelatedFiles(answer: String, projectId: String): List<String> {
        val project = projectManager.getProject(projectId) ?: return emptyList()

        // Паттерны для поиска путей к файлам
        val patterns = listOf(
            Regex("""feature/\w+/\w+\.kt"""),
            Regex("""core/\w+/\w+\.kt"""),
            Regex("""[\w/]+ViewModel\.kt"""),
            Regex("""[\w/]+Repository\.kt"""),
            Regex("""[\w/]+Interactor\.kt"""),
            Regex("""`([^`]+\.kt)`""")
        )

        val files = mutableSetOf<String>()
        patterns.forEach { pattern ->
            pattern.findAll(answer).forEach { match ->
                val path = match.groupValues.getOrElse(1) { match.value }.trim('`')
                files.add(path)
            }
        }

        return files.toList()
    }

    /**
     * Рассчитывает уверенность в ответе.
     */
    private fun calculateConfidence(faqs: List<FaqEntry>, tickets: List<Ticket>): Double {
        var confidence = 0.3 // Базовая уверенность

        // Бонус за найденные FAQ
        if (faqs.isNotEmpty()) {
            confidence += 0.2 * minOf(faqs.size, 3) / 3.0
        }

        // Бонус за найденные тикеты
        if (tickets.isNotEmpty()) {
            confidence += 0.1 * minOf(tickets.size, 3) / 3.0

            // Дополнительный бонус за решённые тикеты
            val resolvedCount = tickets.count { it.status == TicketStatus.RESOLVED }
            confidence += 0.1 * resolvedCount / maxOf(tickets.size, 1).toDouble()
        }

        return minOf(confidence, 1.0)
    }

    /**
     * Быстрый ответ из FAQ без обращения к LLM.
     */
    fun getQuickAnswer(projectId: String, question: String): FaqEntry? {
        return faqManager.findBestMatch(projectId, question)
    }

    /**
     * Создаёт тикет на основе вопроса.
     */
    fun createTicketFromQuestion(
        projectId: String,
        question: String,
        userId: String? = null
    ): Ticket {
        val category = detectCategory(question)
        return ticketManager.createTicket(
            CreateTicketRequest(
                projectId = projectId,
                title = question.take(100),
                description = question,
                category = category,
                userId = userId
            )
        )
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }
}
