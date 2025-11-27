package kz.shprot

import kotlin.math.sqrt

/**
 * Менеджер для векторного поиска по базе знаний
 *
 * Использует косинусное сходство для поиска наиболее релевантных чанков.
 * Поддерживает гибридную фильтрацию с настраиваемыми порогами.
 */
class VectorSearchManager(
    private val databaseManager: DatabaseManager
) {
    /**
     * Параметры фильтрации для гибридного поиска
     */
    data class FilteringConfig(
        val initialCandidates: Int = 20,           // Сколько кандидатов взять на первом этапе
        val primaryThreshold: Double = 0.3,        // Первичный порог (низкий, чтобы не упустить)
        val smartThreshold: Double = 0.5,          // Умный порог для финальной фильтрации
        val topK: Int = 5,                         // Финальное количество результатов
        val removeDuplicates: Boolean = true       // Удалять дубликаты по содержанию
    ) {
        companion object {
            // Дефолтная конфигурация
            val DEFAULT = FilteringConfig()

            // Строгая фильтрация (для точных вопросов)
            val STRICT = FilteringConfig(
                initialCandidates = 15,
                primaryThreshold = 0.4,
                smartThreshold = 0.65,
                topK = 3
            )

            // Мягкая фильтрация (для широких вопросов)
            val LENIENT = FilteringConfig(
                initialCandidates = 30,
                primaryThreshold = 0.2,
                smartThreshold = 0.4,
                topK = 7
            )
        }
    }
    /**
     * Поиск релевантных чанков по эмбеддингу запроса (старый метод для обратной совместимости)
     *
     * @param queryEmbedding эмбеддинг поискового запроса
     * @return список релевантных чанков с их сходством
     */
    fun searchSimilarChunks(queryEmbedding: List<Double>): List<SearchResult> {
        return searchSimilarChunksWithStats(queryEmbedding, FilteringConfig.DEFAULT).results
    }

    /**
     * Поиск релевантных чанков с детальной статистикой (гибридная фильтрация)
     *
     * Алгоритм (вариант 3 - гибридный):
     * 1. Первичная фильтрация: загружаем все чанки и вычисляем similarity
     * 2. Берем топ-N кандидатов с низким порогом (чтобы не упустить)
     * 3. Умная фильтрация: удаляем дубликаты, фильтруем по смарт-порогу
     * 4. Сортируем и возвращаем топ-K с детальной статистикой
     *
     * @param queryEmbedding эмбеддинг поискового запроса
     * @param config конфигурация фильтрации
     * @return результаты с детальной статистикой
     */
    fun searchSimilarChunksWithStats(
        queryEmbedding: List<Double>,
        config: FilteringConfig = FilteringConfig.DEFAULT
    ): SearchResultWithStats {
        val startTime = System.currentTimeMillis()

        // Загружаем все чанки из БД с метаданными документов
        val allChunks = databaseManager.getAllChunksWithMetadata()

        if (allChunks.isEmpty()) {
            println("⚠️ База знаний пуста")
            return SearchResultWithStats(
                results = emptyList(),
                stats = FilteringStats(
                    totalChunks = 0,
                    afterPrimaryFilter = 0,
                    afterSmartFilter = 0,
                    finalResults = 0,
                    avgSimilarityBefore = 0.0,
                    avgSimilarityAfter = 0.0,
                    minSimilarity = 0.0,
                    maxSimilarity = 0.0,
                    similarityDistribution = emptyList(),
                    processingTimeMs = 0,
                    filteringConfig = config
                )
            )
        }

        println("🔍 Гибридный поиск: ${allChunks.size} чанков в базе")

        // 1. Вычисляем сходство для всех чанков
        // ВАЖНО: Пропускаем чанки с несовместимой размерностью эмбеддинга
        val allResults = allChunks.mapNotNull { chunk ->
            // Проверяем размерность эмбеддинга
            if (chunk.embedding.size != queryEmbedding.size) {
                println("  ⚠️ Пропускаем чанк ${chunk.id}: несовместимая размерность эмбеддинга (${chunk.embedding.size} vs ${queryEmbedding.size})")
                return@mapNotNull null
            }

            val similarity = cosineSimilarity(queryEmbedding, chunk.embedding)
            SearchResult(chunk = chunk, similarity = similarity)
        }.sortedByDescending { it.similarity }

        println("  ✓ Обработано ${allResults.size} чанков (пропущено: ${allChunks.size - allResults.size})")

        // Если нет совместимых чанков - возвращаем пустой результат
        if (allResults.isEmpty()) {
            println("⚠️ Нет чанков с совместимой размерностью эмбеддинга")
            return SearchResultWithStats(
                results = emptyList(),
                stats = FilteringStats(
                    totalChunks = allChunks.size,
                    afterPrimaryFilter = 0,
                    afterSmartFilter = 0,
                    finalResults = 0,
                    avgSimilarityBefore = 0.0,
                    avgSimilarityAfter = 0.0,
                    minSimilarity = 0.0,
                    maxSimilarity = 0.0,
                    similarityDistribution = emptyList(),
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    filteringConfig = config
                )
            )
        }

        val avgSimilarityBefore = allResults.map { it.similarity }.average()

        // 2. Первичная фильтрация: берем топ-N кандидатов с низким порогом
        val primaryFiltered = allResults
            .filter { it.similarity >= config.primaryThreshold }
            .take(config.initialCandidates)

        println("  ✓ Первичная фильтрация: ${primaryFiltered.size} кандидатов (порог: ${config.primaryThreshold})")

        // 3. Умная фильтрация: удаляем дубликаты и применяем смарт-порог
        var smartFiltered = primaryFiltered
            .filter { it.similarity >= config.smartThreshold }

        println("  ✓ Умная фильтрация: ${smartFiltered.size} чанков (порог: ${config.smartThreshold})")

        // Удаляем дубликаты (если включено)
        if (config.removeDuplicates && smartFiltered.isNotEmpty()) {
            smartFiltered = removeDuplicateChunks(smartFiltered)
            println("  ✓ Удаление дубликатов: осталось ${smartFiltered.size} чанков")
        }

        // 4. Берем финальный топ-K
        val finalResults = smartFiltered.take(config.topK)

        val avgSimilarityAfter = if (finalResults.isNotEmpty()) {
            finalResults.map { it.similarity }.average()
        } else 0.0

        val processingTime = System.currentTimeMillis() - startTime

        // Формируем статистику
        val stats = FilteringStats(
            totalChunks = allChunks.size,
            afterPrimaryFilter = primaryFiltered.size,
            afterSmartFilter = smartFiltered.size,
            finalResults = finalResults.size,
            avgSimilarityBefore = avgSimilarityBefore,
            avgSimilarityAfter = avgSimilarityAfter,
            minSimilarity = finalResults.minOfOrNull { it.similarity } ?: 0.0,
            maxSimilarity = finalResults.maxOfOrNull { it.similarity } ?: 0.0,
            similarityDistribution = finalResults.map { it.similarity },
            processingTimeMs = processingTime,
            filteringConfig = config
        )

        println("✅ Найдено релевантных чанков: ${finalResults.size} (время: ${processingTime}ms)")
        finalResults.forEachIndexed { index, result ->
            println("  ${index + 1}. Similarity: ${String.format("%.3f", result.similarity)} - ${result.chunk.content.take(80)}...")
        }

        return SearchResultWithStats(
            results = finalResults,
            stats = stats
        )
    }

    /**
     * Удаление дубликатов чанков по содержанию
     *
     * Чанки считаются дубликатами если у них схожее содержание (>70% совпадения)
     */
    private fun removeDuplicateChunks(chunks: List<SearchResult>): List<SearchResult> {
        val unique = mutableListOf<SearchResult>()

        for (chunk in chunks) {
            val isDuplicate = unique.any { existing ->
                val similarity = textSimilarity(existing.chunk.content, chunk.chunk.content)
                similarity > 0.7 // 70% схожести считаем дубликатом
            }

            if (!isDuplicate) {
                unique.add(chunk)
            }
        }

        return unique
    }

    /**
     * Простая оценка схожести текстов (по словам)
     */
    private fun textSimilarity(text1: String, text2: String): Double {
        val words1 = text1.lowercase().split(Regex("\\s+")).toSet()
        val words2 = text2.lowercase().split(Regex("\\s+")).toSet()

        if (words1.isEmpty() && words2.isEmpty()) return 1.0
        if (words1.isEmpty() || words2.isEmpty()) return 0.0

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return intersection.toDouble() / union.toDouble()
    }

    /**
     * Вычисление косинусного сходства между двумя векторами
     *
     * Формула: cos(θ) = (A · B) / (||A|| * ||B||)
     * где:
     * - A · B - скалярное произведение
     * - ||A|| - норма вектора A
     * - ||B|| - норма вектора B
     *
     * Результат: число от -1 до 1 (где 1 - полное совпадение)
     */
    private fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        require(vec1.size == vec2.size) { "Векторы должны быть одинаковой размерности" }

        // Скалярное произведение
        val dotProduct = vec1.zip(vec2).sumOf { (a, b) -> a * b }

        // Нормы векторов
        val norm1 = sqrt(vec1.sumOf { it * it })
        val norm2 = sqrt(vec2.sumOf { it * it })

        // Косинусное сходство
        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (norm1 * norm2)
        } else {
            0.0
        }
    }

    /**
     * Получение контекста для RAG из топ-K чанков
     *
     * @param queryEmbedding эмбеддинг запроса
     * @return объединенный текст из релевантных чанков
     */
    fun getRelevantContext(queryEmbedding: List<Double>): String {
        val results = searchSimilarChunks(queryEmbedding)

        if (results.isEmpty()) {
            return ""
        }

        // Объединяем контент чанков через двойной перевод строки
        return results.joinToString("\n\n") { result ->
            result.chunk.content
        }
    }
}

/**
 * Результат поиска: чанк с метаданными + его сходство с запросом
 */
data class SearchResult(
    val chunk: ChunkWithMetadata,
    val similarity: Double
)

/**
 * Результат поиска с детальной статистикой фильтрации
 */
data class SearchResultWithStats(
    val results: List<SearchResult>,
    val stats: FilteringStats
)

/**
 * Статистика гибридной фильтрации
 */
data class FilteringStats(
    val totalChunks: Int,                              // Всего чанков в базе
    val afterPrimaryFilter: Int,                       // После первичной фильтрации
    val afterSmartFilter: Int,                         // После умной фильтрации
    val finalResults: Int,                             // Финальное количество результатов
    val avgSimilarityBefore: Double,                   // Средний similarity до фильтрации
    val avgSimilarityAfter: Double,                    // Средний similarity после фильтрации
    val minSimilarity: Double,                         // Минимальный similarity в результатах
    val maxSimilarity: Double,                         // Максимальный similarity в результатах
    val similarityDistribution: List<Double>,          // Распределение similarity
    val processingTimeMs: Long,                        // Время обработки в мс
    val filteringConfig: VectorSearchManager.FilteringConfig  // Использованная конфигурация
)
