package kz.shprot

import kotlin.math.sqrt

/**
 * Менеджер для переранжирования (reranking) результатов поиска
 *
 * Использует модель nomic-embed-text через Ollama для более точной оценки релевантности.
 * В отличие от простого косинусного сходства на этапе векторного поиска,
 * reranking модель может учитывать более сложные семантические связи.
 *
 * Алгоритм:
 * 1. Получаем кандидатов из первичного поиска
 * 2. Генерируем эмбеддинги для запроса и каждого чанка через reranking модель
 * 3. Вычисляем косинусное сходство с использованием reranking эмбеддингов
 * 4. Переранжируем результаты по новым скорам
 * 5. Возвращаем топ-K
 */
class RerankingManager(
    private val ollamaClient: OllamaClient
) {
    /**
     * Переранжирование результатов поиска
     *
     * @param query текст запроса пользователя
     * @param candidates список кандидатов для переранжирования
     * @param topK сколько топ результатов вернуть после reranking
     * @return переранжированный список результатов с новыми скорами
     */
    suspend fun rerankResults(
        query: String,
        candidates: List<SearchResult>,
        topK: Int = 5
    ): RerankingResult {
        if (candidates.isEmpty()) {
            return RerankingResult(
                results = emptyList(),
                stats = RerankingStats(
                    totalCandidates = 0,
                    rerankedCount = 0,
                    avgScoreBefore = 0.0,
                    avgScoreAfter = 0.0,
                    scoreImprovement = 0.0,
                    processingTimeMs = 0
                )
            )
        }

        val startTime = System.currentTimeMillis()
        println("🔄 Начало reranking для ${candidates.size} кандидатов (топ-$topK)")

        // Генерируем эмбеддинг для запроса через reranking модель
        val queryEmbedding = runCatching {
            ollamaClient.generateRerankingEmbedding(query)
        }.getOrElse { e ->
            println("⚠️ Не удалось сгенерировать reranking эмбеддинг для запроса: ${e.message}")
            // Если reranking не работает - возвращаем исходные результаты
            return RerankingResult(
                results = candidates.take(topK),
                stats = RerankingStats(
                    totalCandidates = candidates.size,
                    rerankedCount = 0,
                    avgScoreBefore = candidates.map { it.similarity }.average(),
                    avgScoreAfter = candidates.take(topK).map { it.similarity }.average(),
                    scoreImprovement = 0.0,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            )
        }

        // Генерируем эмбеддинги для каждого кандидата и вычисляем новые скоры
        val rerankedResults = candidates.mapNotNull { candidate ->
            runCatching {
                // Генерируем эмбеддинг для чанка через reranking модель
                val chunkEmbedding = ollamaClient.generateRerankingEmbedding(candidate.chunk.content)

                // Вычисляем косинусное сходство с reranking эмбеддингами
                val rerankScore = cosineSimilarity(queryEmbedding, chunkEmbedding)

                RerankingSearchResult(
                    chunk = candidate.chunk,
                    originalSimilarity = candidate.similarity,
                    rerankScore = rerankScore
                )
            }.getOrElse { e ->
                println("⚠️ Ошибка при reranking чанка: ${e.message}")
                null // Пропускаем чанки с ошибками
            }
        }

        // Сортируем по rerank скору и берем топ-K
        val topResults = rerankedResults
            .sortedByDescending { it.rerankScore }
            .take(topK)

        val processingTime = System.currentTimeMillis() - startTime

        // Вычисляем статистику
        val avgScoreBefore = candidates.map { it.similarity }.average()
        val avgScoreAfter = rerankedResults.map { it.rerankScore }.average()
        val scoreImprovement = ((avgScoreAfter - avgScoreBefore) / avgScoreBefore) * 100

        val stats = RerankingStats(
            totalCandidates = candidates.size,
            rerankedCount = rerankedResults.size,
            avgScoreBefore = avgScoreBefore,
            avgScoreAfter = avgScoreAfter,
            scoreImprovement = scoreImprovement,
            processingTimeMs = processingTime
        )

        println("✅ Reranking завершен: ${rerankedResults.size} чанков, улучшение скора: ${String.format("%.2f", scoreImprovement)}%, время: ${processingTime}ms")
        topResults.forEachIndexed { index, result ->
            println("  ${index + 1}. Rerank: ${String.format("%.3f", result.rerankScore)} (было: ${String.format("%.3f", result.originalSimilarity)}) - ${result.chunk.content.take(80)}...")
        }

        return RerankingResult(
            results = topResults.map {
                SearchResult(chunk = it.chunk, similarity = it.rerankScore)
            },
            stats = stats
        )
    }

    /**
     * Вычисление косинусного сходства между двумя векторами
     */
    private fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        require(vec1.size == vec2.size) { "Векторы должны быть одинаковой размерности" }

        val dotProduct = vec1.zip(vec2).sumOf { (a, b) -> a * b }
        val norm1 = sqrt(vec1.sumOf { it * it })
        val norm2 = sqrt(vec2.sumOf { it * it })

        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (norm1 * norm2)
        } else {
            0.0
        }
    }
}

/**
 * Результат переранжирования с детальной статистикой
 */
data class RerankingResult(
    val results: List<SearchResult>,
    val stats: RerankingStats
)

/**
 * Статистика процесса reranking
 */
data class RerankingStats(
    val totalCandidates: Int,       // Сколько кандидатов пришло на вход
    val rerankedCount: Int,          // Сколько успешно переранжировано
    val avgScoreBefore: Double,      // Средний скор до reranking
    val avgScoreAfter: Double,       // Средний скор после reranking
    val scoreImprovement: Double,    // Улучшение скора в процентах
    val processingTimeMs: Long       // Время обработки в миллисекундах
)

/**
 * Результат поиска с информацией о reranking
 */
data class RerankingSearchResult(
    val chunk: ChunkWithMetadata,
    val originalSimilarity: Double,  // Исходный скор от векторного поиска
    val rerankScore: Double          // Новый скор после reranking
)
