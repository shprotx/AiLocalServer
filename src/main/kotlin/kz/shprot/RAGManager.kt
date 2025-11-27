package kz.shprot

import kz.shprot.models.Message

/**
 * Менеджер для RAG (Retrieval-Augmented Generation)
 *
 * Интегрирует векторный поиск с основной LLM используя гибридный подход:
 * 1. Генерирует эмбеддинг для запроса пользователя (через bge-m3)
 * 2. Ищет релевантные чанки в базе знаний (гибридная фильтрация)
 * 3. Опционально: переранжирование топ-N чанков (через nomic-embed-text)
 * 4. Добавляет найденные чанки в контекст для LLM
 * 5. LLM генерирует ответ с учетом базы знаний
 */
class RAGManager(
    private val embeddingsManager: EmbeddingsManager,
    private val vectorSearchManager: VectorSearchManager,
    private val rerankingManager: RerankingManager
) {
    /**
     * Конфигурация RAG пайплайна
     */
    data class RAGConfig(
        val filteringConfig: VectorSearchManager.FilteringConfig = VectorSearchManager.FilteringConfig.DEFAULT,
        val useReranking: Boolean = true,
        val rerankingTopK: Int = 5
    )

    /**
     * Информация об источнике (документе) использованном в RAG
     */
    data class SourceInfo(
        val documentId: Int,
        val filename: String,
        val fileType: String
    )

    /**
     * Детальная информация о RAG обогащении
     */
    data class RAGEnrichmentInfo(
        val augmentedMessages: List<Message>,
        val ragUsed: Boolean,
        val ragContext: String?,
        val chunksCount: Int,
        val similarityScores: List<Double>,
        // Информация об источниках (документах)
        val sources: List<SourceInfo> = emptyList(),
        // Новые поля для детальной статистики
        val filteringStats: FilteringStats? = null,
        val rerankingStats: RerankingStats? = null
    )

    /**
     * Обогащение промпта контекстом из базы знаний (упрощенный метод)
     *
     * @param userQuery запрос пользователя
     * @param originalMessages исходный список сообщений для LLM
     * @param config конфигурация RAG пайплайна
     * @return Triple(augmentedMessages, ragUsed, ragContext)
     */
    suspend fun augmentPromptWithKnowledge(
        userQuery: String,
        originalMessages: List<Message>,
        config: RAGConfig = RAGConfig()
    ): Triple<List<Message>, Boolean, String?> {
        val enrichmentInfo = augmentPromptWithKnowledgeDetailed(userQuery, originalMessages, config)
        return Triple(enrichmentInfo.augmentedMessages, enrichmentInfo.ragUsed, enrichmentInfo.ragContext)
    }

    /**
     * Обогащение промпта контекстом из базы знаний (с детальной информацией)
     *
     * Гибридный пайплайн:
     * 1. Генерация эмбеддинга через bge-m3
     * 2. Гибридная фильтрация (первичная + умная)
     * 3. Опциональный reranking через nomic-embed-text
     * 4. Построение обогащенного промпта
     *
     * @param userQuery запрос пользователя
     * @param originalMessages исходный список сообщений для LLM
     * @param config конфигурация RAG пайплайна
     * @return RAGEnrichmentInfo с полной информацией о обогащении
     */
    suspend fun augmentPromptWithKnowledgeDetailed(
        userQuery: String,
        originalMessages: List<Message>,
        config: RAGConfig = RAGConfig()
    ): RAGEnrichmentInfo {
        println("🚀 RAG Pipeline: фильтрация=${config.filteringConfig}, reranking=${config.useReranking}")

        // Генерируем эмбеддинг для запроса (через bge-m3)
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
                similarityScores = emptyList(),
                filteringStats = null,
                rerankingStats = null
            )
        }

        // Ищем релевантные чанки с гибридной фильтрацией
        val searchResultWithStats = vectorSearchManager.searchSimilarChunksWithStats(
            queryEmbedding,
            config.filteringConfig
        )

        val filteringStats = searchResultWithStats.stats
        var searchResults = searchResultWithStats.results

        // Если релевантного контекста нет - возвращаем исходные сообщения
        if (searchResults.isEmpty()) {
            println("ℹ️ Релевантная информация в базе знаний не найдена")
            return RAGEnrichmentInfo(
                augmentedMessages = originalMessages,
                ragUsed = false,
                ragContext = null,
                chunksCount = 0,
                similarityScores = emptyList(),
                filteringStats = filteringStats,
                rerankingStats = null
            )
        }

        // Опциональный reranking
        var rerankingStats: RerankingStats? = null
        if (config.useReranking && searchResults.isNotEmpty()) {
            println("🔄 Запуск reranking для ${searchResults.size} кандидатов")
            val rerankingResult = rerankingManager.rerankResults(
                query = userQuery,
                candidates = searchResults,
                topK = config.rerankingTopK
            )
            searchResults = rerankingResult.results
            rerankingStats = rerankingResult.stats
        }

        // Формируем контекст из найденных чанков
        val relevantContext = searchResults.joinToString("\n\n") { it.chunk.content }
        val similarityScores = searchResults.map { it.similarity }

        // Собираем информацию об источниках (уникальные документы)
        val sources = searchResults
            .map { result ->
                SourceInfo(
                    documentId = result.chunk.documentId,
                    filename = result.chunk.filename,
                    fileType = result.chunk.fileType
                )
            }
            .distinctBy { it.documentId }  // Убираем дубликаты по documentId

        println("✅ Финальный контекст: ${relevantContext.length} символов, ${searchResults.size} чанков из ${sources.size} документов")

        // Создаем обогащенный список сообщений с указанием источников
        val augmentedMessages = buildAugmentedMessages(originalMessages, relevantContext, sources)

        return RAGEnrichmentInfo(
            augmentedMessages = augmentedMessages,
            ragUsed = true,
            ragContext = relevantContext,
            chunksCount = searchResults.size,
            similarityScores = similarityScores,
            sources = sources,
            filteringStats = filteringStats,
            rerankingStats = rerankingStats
        )
    }

    /**
     * Построение обогащенного списка сообщений
     *
     * Вставляем контекст из базы знаний в system prompt с требованием указания источников
     */
    private fun buildAugmentedMessages(
        originalMessages: List<Message>,
        knowledgeContext: String,
        sources: List<SourceInfo>
    ): List<Message> {
        val augmentedMessages = mutableListOf<Message>()

        // Ищем существующий system prompt
        val systemMessageIndex = originalMessages.indexOfFirst { it.role == "system" }

        // Формируем список источников для промпта
        val sourcesText = sources.joinToString("\n") { "- ${it.filename} (ID: ${it.documentId})" }

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
                📄 ИСПОЛЬЗОВАННЫЕ ИСТОЧНИКИ:
                $sourcesText
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                ВАЖНО: Вся информация для ответа на вопрос пользователя УЖЕ НАХОДИТСЯ ВЫШЕ в разделе "КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ".

                КРИТИЧЕСКИ ВАЖНЫЕ ИНСТРУКЦИИ:
                1. Внимательно прочитай контекст выше
                2. Используй ТОЛЬКО эту информацию для формирования ответа
                3. НЕ переспрашивай и НЕ проси предоставить текст - он уже есть в контексте
                4. Отвечай конкретно на вопрос пользователя, используя информацию из контекста
                5. **ОБЯЗАТЕЛЬНО УКАЗЫВАЙ ИСТОЧНИКИ**: В КОНЦЕ своего ответа ВСЕГДА добавляй раздел "Источники:" со списком использованных документов в формате [источник: название_файла]
                6. Если в контексте нет нужной информации - так и скажи, но НЕ проси текст

                ФОРМАТ ОТВЕТА:
                [Твой основной ответ на вопрос пользователя]

                Источники:
                - [источник: название_файла_1]
                - [источник: название_файла_2]
                ...
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
                📄 ИСПОЛЬЗОВАННЫЕ ИСТОЧНИКИ:
                $sourcesText
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                ВАЖНО: Вся информация для ответа на вопрос пользователя УЖЕ НАХОДИТСЯ ВЫШЕ в разделе "КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ".

                КРИТИЧЕСКИ ВАЖНЫЕ ИНСТРУКЦИИ:
                1. Внимательно прочитай контекст выше
                2. Используй ТОЛЬКО эту информацию для формирования ответа
                3. НЕ переспрашивай и НЕ проси предоставить текст - он уже есть в контексте
                4. Отвечай конкретно на вопрос пользователя, используя информацию из контекста
                5. **ОБЯЗАТЕЛЬНО УКАЗЫВАЙ ИСТОЧНИКИ**: В КОНЦЕ своего ответа ВСЕГДА добавляй раздел "Источники:" со списком использованных документов в формате [источник: название_файла]
                6. Если в контексте нет нужной информации - так и скажи, но НЕ проси текст

                ФОРМАТ ОТВЕТА:
                [Твой основной ответ на вопрос пользователя]

                Источники:
                - [источник: название_файла_1]
                - [источник: название_файла_2]
                ...
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
