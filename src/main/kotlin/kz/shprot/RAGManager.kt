package kz.shprot

import kz.shprot.models.Message

/**
 * Менеджер для RAG (Retrieval-Augmented Generation)
 *
 * Интегрирует векторный поиск с основной LLM:
 * 1. Генерирует эмбеддинг для запроса пользователя
 * 2. Ищет релевантные чанки в базе знаний
 * 3. Добавляет найденные чанки в контекст для LLM
 * 4. LLM генерирует ответ с учетом базы знаний
 */
class RAGManager(
    private val embeddingsManager: EmbeddingsManager,
    private val vectorSearchManager: VectorSearchManager
) {
    /**
     * Детальная информация о RAG обогащении
     */
    data class RAGEnrichmentInfo(
        val augmentedMessages: List<Message>,
        val ragUsed: Boolean,
        val ragContext: String?,
        val chunksCount: Int,
        val similarityScores: List<Double>
    )

    /**
     * Обогащение промпта контекстом из базы знаний
     *
     * @param userQuery запрос пользователя
     * @param originalMessages исходный список сообщений для LLM
     * @return Triple(augmentedMessages, ragUsed, ragContext)
     */
    suspend fun augmentPromptWithKnowledge(
        userQuery: String,
        originalMessages: List<Message>
    ): Triple<List<Message>, Boolean, String?> {
        val enrichmentInfo = augmentPromptWithKnowledgeDetailed(userQuery, originalMessages)
        return Triple(enrichmentInfo.augmentedMessages, enrichmentInfo.ragUsed, enrichmentInfo.ragContext)
    }

    /**
     * Обогащение промпта контекстом из базы знаний (с детальной информацией)
     *
     * @param userQuery запрос пользователя
     * @param originalMessages исходный список сообщений для LLM
     * @return RAGEnrichmentInfo с полной информацией о обогащении
     */
    suspend fun augmentPromptWithKnowledgeDetailed(
        userQuery: String,
        originalMessages: List<Message>
    ): RAGEnrichmentInfo {
        // Генерируем эмбеддинг для запроса
        val queryEmbedding = runCatching {
            embeddingsManager.generateQueryEmbedding(userQuery)
        }.getOrElse { e ->
            println("⚠️ Не удалось сгенерировать эмбеддинг для запроса: ${e.message}")
            // Если Ollama недоступна - возвращаем исходные сообщения
            return RAGEnrichmentInfo(
                augmentedMessages = originalMessages,
                ragUsed = false,
                ragContext = null,
                chunksCount = 0,
                similarityScores = emptyList()
            )
        }

        // Ищем релевантные чанки (получаем детальную информацию)
        val searchResults = vectorSearchManager.searchSimilarChunks(queryEmbedding)

        // Если релевантного контекста нет - возвращаем исходные сообщения
        if (searchResults.isEmpty()) {
            println("ℹ️ Релевантная информация в базе знаний не найдена")
            return RAGEnrichmentInfo(
                augmentedMessages = originalMessages,
                ragUsed = false,
                ragContext = null,
                chunksCount = 0,
                similarityScores = emptyList()
            )
        }

        // Формируем контекст из найденных чанков
        val relevantContext = searchResults.joinToString("\n\n") { it.chunk.content }
        val similarityScores = searchResults.map { it.similarity }

        // Проверяем реальную релевантность по среднему similarity
        val avgSimilarity = similarityScores.average()
        val relevanceThreshold = 0.65 // Порог реальной релевантности

        if (avgSimilarity < relevanceThreshold) {
            println("⚠️ Найденные чанки имеют низкую релевантность (avg similarity: %.3f < %.2f)".format(avgSimilarity, relevanceThreshold))
            println("ℹ️ Контекст из базы знаний не будет использован (нерелевантен для данного вопроса)")
            return RAGEnrichmentInfo(
                augmentedMessages = originalMessages,
                ragUsed = false,
                ragContext = null, // Не показываем нерелевантный контекст
                chunksCount = 0,
                similarityScores = emptyList()
            )
        }

        println("✅ Найден релевантный контекст из базы знаний (${relevantContext.length} символов, ${searchResults.size} чанков, avg similarity: %.3f)".format(avgSimilarity))

        // Создаем обогащенный список сообщений
        val augmentedMessages = buildAugmentedMessages(originalMessages, relevantContext)

        return RAGEnrichmentInfo(
            augmentedMessages = augmentedMessages,
            ragUsed = true,
            ragContext = relevantContext,
            chunksCount = searchResults.size,
            similarityScores = similarityScores
        )
    }

    /**
     * Построение обогащенного списка сообщений
     *
     * Вставляем контекст из базы знаний в system prompt
     */
    private fun buildAugmentedMessages(
        originalMessages: List<Message>,
        knowledgeContext: String
    ): List<Message> {
        val augmentedMessages = mutableListOf<Message>()

        // Ищем существующий system prompt
        val systemMessageIndex = originalMessages.indexOfFirst { it.role == "system" }

        if (systemMessageIndex >= 0) {
            // Если есть system prompt - дополняем его
            val existingSystemMessage = originalMessages[systemMessageIndex]
            val augmentedSystemPrompt = """
                ${existingSystemMessage.text}

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                📚 КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                $knowledgeContext

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                ВАЖНО: Вся информация для ответа на вопрос пользователя УЖЕ НАХОДИТСЯ ВЫШЕ в разделе "КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ".

                ИНСТРУКЦИЯ:
                1. Внимательно прочитай контекст выше
                2. Используй ТОЛЬКО эту информацию для формирования ответа
                3. НЕ переспрашивай и НЕ проси предоставить текст - он уже есть в контексте
                4. Отвечай конкретно на вопрос пользователя, используя информацию из контекста
                5. Если в контексте нет нужной информации - так и скажи, но НЕ проси текст
            """.trimIndent()

            augmentedMessages.add(Message(role = "system", text = augmentedSystemPrompt))

            // Добавляем остальные сообщения (кроме оригинального system)
            augmentedMessages.addAll(originalMessages.filterIndexed { index, _ -> index != systemMessageIndex })
        } else {
            // Если нет system prompt - создаем новый с контекстом
            val newSystemPrompt = """
                Ты - полезный ассистент с доступом к базе знаний.

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                📚 КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                $knowledgeContext

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                ВАЖНО: Вся информация для ответа на вопрос пользователя УЖЕ НАХОДИТСЯ ВЫШЕ в разделе "КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ".

                ИНСТРУКЦИЯ:
                1. Внимательно прочитай контекст выше
                2. Используй ТОЛЬКО эту информацию для формирования ответа
                3. НЕ переспрашивай и НЕ проси предоставить текст - он уже есть в контексте
                4. Отвечай конкретно на вопрос пользователя, используя информацию из контекста
                5. Если в контексте нет нужной информации - так и скажи, но НЕ проси текст
            """.trimIndent()

            augmentedMessages.add(Message(role = "system", text = newSystemPrompt))
            augmentedMessages.addAll(originalMessages)
        }

        return augmentedMessages
    }

    /**
     * Проверка доступности RAG системы
     */
    suspend fun isAvailable(): Boolean {
        return runCatching {
            val stats = embeddingsManager.getKnowledgeBaseStats()
            println("📚 База знаний: ${stats.totalDocuments} документов, ${stats.totalChunks} чанков")
            stats.totalChunks > 0
        }.getOrElse { false }
    }
}
