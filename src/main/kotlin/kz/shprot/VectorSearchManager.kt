package kz.shprot

import kotlin.math.sqrt

/**
 * Менеджер для векторного поиска по базе знаний
 *
 * Использует косинусное сходство для поиска наиболее релевантных чанков
 */
class VectorSearchManager(
    private val databaseManager: DatabaseManager,
    private val topK: Int = 5,                  // Сколько топ результатов возвращать
    private val similarityThreshold: Double = 0.5   // Минимальное сходство для фильтрации
) {
    /**
     * Поиск релевантных чанков по эмбеддингу запроса
     *
     * Алгоритм:
     * 1. Загружаем все чанки из БД
     * 2. Вычисляем косинусное сходство для каждого чанка
     * 3. Фильтруем по threshold
     * 4. Сортируем по убыванию сходства
     * 5. Возвращаем топ-K результатов
     *
     * @param queryEmbedding эмбеддинг поискового запроса
     * @return список релевантных чанков с их сходством
     */
    fun searchSimilarChunks(queryEmbedding: List<Double>): List<SearchResult> {
        // Загружаем все чанки из БД
        val allChunks = databaseManager.getAllChunks()

        if (allChunks.isEmpty()) {
            println("⚠️ База знаний пуста")
            return emptyList()
        }

        println("🔍 Поиск в базе знаний: ${allChunks.size} чанков")

        // Вычисляем сходство для каждого чанка
        val results = allChunks.map { chunk ->
            val similarity = cosineSimilarity(queryEmbedding, chunk.embedding)
            SearchResult(
                chunk = chunk,
                similarity = similarity
            )
        }

        // Фильтруем по threshold, сортируем и берем топ-K
        val topResults = results
            .filter { it.similarity >= similarityThreshold }
            .sortedByDescending { it.similarity }
            .take(topK)

        println("✅ Найдено релевантных чанков: ${topResults.size} (threshold=$similarityThreshold)")
        topResults.forEachIndexed { index, result ->
            println("  ${index + 1}. Similarity: ${String.format("%.3f", result.similarity)} - ${result.chunk.content.take(100)}...")
        }

        return topResults
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
 * Результат поиска: чанк + его сходство с запросом
 */
data class SearchResult(
    val chunk: ChunkData,
    val similarity: Double
)
