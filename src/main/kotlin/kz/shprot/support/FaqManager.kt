package kz.shprot.support

import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер FAQ (часто задаваемых вопросов).
 * Управляет базой знаний для быстрых ответов на типовые вопросы.
 */
class FaqManager(
    private val dataPath: String = "support_data/faq.json"
) {
    private val faqs = ConcurrentHashMap<Int, FaqEntry>()
    private var nextId = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        ensureDataDirectory()
        loadFaqs()
    }

    /**
     * Добавляет новый FAQ.
     */
    fun addFaq(
        projectId: String,
        question: String,
        answer: String,
        category: TicketCategory = TicketCategory.OTHER,
        keywords: List<String> = emptyList()
    ): FaqEntry {
        val faq = FaqEntry(
            id = nextId++,
            projectId = projectId,
            question = question,
            answer = answer,
            category = category,
            keywords = keywords
        )
        faqs[faq.id] = faq
        saveFaqs()
        println("[FaqManager] Added FAQ #${faq.id}: ${faq.question.take(50)}...")
        return faq
    }

    /**
     * Получает FAQ по ID.
     */
    fun getFaq(faqId: Int): FaqEntry? = faqs[faqId]

    /**
     * Получает все FAQ для проекта.
     */
    fun getFaqsByProject(projectId: String): List<FaqEntry> {
        return faqs.values
            .filter { it.projectId == projectId }
            .sortedByDescending { it.helpfulCount }
    }

    /**
     * Получает FAQ по категории.
     */
    fun getFaqsByCategory(projectId: String, category: TicketCategory): List<FaqEntry> {
        return getFaqsByProject(projectId)
            .filter { it.category == category }
    }

    /**
     * Поиск FAQ по тексту вопроса.
     * Использует простой текстовый поиск + ключевые слова.
     */
    fun searchFaqs(projectId: String, query: String, limit: Int = 5): List<FaqEntry> {
        val lowerQuery = query.lowercase()
        val queryWords = lowerQuery.split(Regex("\\s+")).filter { it.length > 2 }

        return getFaqsByProject(projectId)
            .map { faq ->
                val score = calculateRelevanceScore(faq, lowerQuery, queryWords)
                faq to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Находит наиболее релевантный FAQ для вопроса.
     */
    fun findBestMatch(projectId: String, query: String): FaqEntry? {
        return searchFaqs(projectId, query, limit = 1).firstOrNull()
    }

    /**
     * Отмечает FAQ как полезный.
     */
    fun markHelpful(faqId: Int): FaqEntry? {
        val faq = faqs[faqId] ?: return null
        val updated = faq.copy(helpfulCount = faq.helpfulCount + 1)
        faqs[faqId] = updated
        saveFaqs()
        return updated
    }

    /**
     * Отмечает FAQ как неполезный.
     */
    fun markNotHelpful(faqId: Int): FaqEntry? {
        val faq = faqs[faqId] ?: return null
        val updated = faq.copy(notHelpfulCount = faq.notHelpfulCount + 1)
        faqs[faqId] = updated
        saveFaqs()
        return updated
    }

    /**
     * Связывает FAQ с тикетом.
     */
    fun linkToTicket(faqId: Int, ticketId: Int): FaqEntry? {
        val faq = faqs[faqId] ?: return null
        if (ticketId in faq.relatedTicketIds) return faq

        val updated = faq.copy(relatedTicketIds = faq.relatedTicketIds + ticketId)
        faqs[faqId] = updated
        saveFaqs()
        return updated
    }

    /**
     * Обновляет FAQ.
     */
    fun updateFaq(
        faqId: Int,
        question: String? = null,
        answer: String? = null,
        category: TicketCategory? = null,
        keywords: List<String>? = null
    ): FaqEntry? {
        val faq = faqs[faqId] ?: return null
        val updated = faq.copy(
            question = question ?: faq.question,
            answer = answer ?: faq.answer,
            category = category ?: faq.category,
            keywords = keywords ?: faq.keywords
        )
        faqs[faqId] = updated
        saveFaqs()
        println("[FaqManager] Updated FAQ #$faqId")
        return updated
    }

    /**
     * Удаляет FAQ.
     */
    fun deleteFaq(faqId: Int): Boolean {
        val removed = faqs.remove(faqId) != null
        if (removed) {
            saveFaqs()
            println("[FaqManager] Deleted FAQ #$faqId")
        }
        return removed
    }

    /**
     * Получает краткую информацию о FAQ для ответа.
     */
    fun getFaqSummaries(projectId: String, limit: Int = 5): List<FaqSummary> {
        return getFaqsByProject(projectId)
            .take(limit)
            .map { FaqSummary(it.id, it.question, it.category) }
    }

    /**
     * Рассчитывает релевантность FAQ к запросу.
     */
    private fun calculateRelevanceScore(faq: FaqEntry, query: String, queryWords: List<String>): Int {
        var score = 0

        // Точное совпадение вопроса
        if (faq.question.lowercase().contains(query)) {
            score += 100
        }

        // Совпадение ключевых слов
        val faqKeywordsLower = faq.keywords.map { it.lowercase() }
        queryWords.forEach { word ->
            if (faqKeywordsLower.any { it.contains(word) }) {
                score += 20
            }
        }

        // Совпадение слов в вопросе
        val questionWords = faq.question.lowercase().split(Regex("\\s+"))
        queryWords.forEach { word ->
            if (questionWords.any { it.contains(word) }) {
                score += 10
            }
        }

        // Совпадение слов в ответе
        val answerWords = faq.answer.lowercase().split(Regex("\\s+"))
        queryWords.forEach { word ->
            if (answerWords.any { it.contains(word) }) {
                score += 5
            }
        }

        // Бонус за высокий рейтинг полезности
        score += faq.helpfulCount * 2
        score -= faq.notHelpfulCount

        return score
    }

    private fun ensureDataDirectory() {
        val dir = File(dataPath).parentFile
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
            println("[FaqManager] Created data directory: ${dir.path}")
        }
    }

    private fun loadFaqs() {
        runCatching {
            val file = File(dataPath)
            if (file.exists()) {
                val config = json.decodeFromString<FaqDataConfig>(file.readText())
                faqs.clear()
                config.faqs.forEach { faqs[it.id] = it }
                nextId = config.nextFaqId
                println("[FaqManager] Loaded ${faqs.size} FAQs")
            }
        }.onFailure {
            println("[FaqManager] Failed to load FAQs: ${it.message}")
        }
    }

    private fun saveFaqs() {
        runCatching {
            val config = FaqDataConfig(
                faqs = faqs.values.toList().sortedBy { it.id },
                nextFaqId = nextId
            )
            File(dataPath).writeText(json.encodeToString(FaqDataConfig.serializer(), config))
        }.onFailure {
            println("[FaqManager] Failed to save FAQs: ${it.message}")
        }
    }
}
