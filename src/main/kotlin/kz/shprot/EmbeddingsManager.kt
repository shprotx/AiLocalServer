package kz.shprot

import java.io.InputStream

/**
 * Менеджер для генерации и хранения эмбеддингов
 *
 * Координирует работу DocumentProcessor, OllamaClient и DatabaseManager
 * для полного цикла обработки документов:
 * 1. Парсинг файла
 * 2. Разбивка на чанки
 * 3. Генерация эмбеддингов
 * 4. Сохранение в БД
 */
class EmbeddingsManager(
    private val ollamaClient: OllamaClient,
    private val databaseManager: DatabaseManager,
    private val documentProcessor: DocumentProcessor
) {
    /**
     * Обработка загруженного файла
     *
     * Полный пайплайн:
     * 1. Парсинг файла (текст или PDF)
     * 2. Разбивка на чанки
     * 3. Генерация эмбеддинга для каждого чанка
     * 4. Сохранение в БД
     *
     * @param fileContent содержимое файла
     * @param filename имя файла
     * @return ID созданного документа в БД
     */
    suspend fun processAndStoreDocument(fileContent: InputStream, filename: String): Int {
        println("🚀 Начало обработки файла: $filename")

        // Проверяем доступность Ollama
        if (!ollamaClient.isAvailable()) {
            throw IllegalStateException("Ollama сервер недоступен. Убедитесь что Ollama запущена на localhost:11434")
        }

        // 1. Парсинг и чанкирование
        val chunks = documentProcessor.processFile(fileContent, filename)
        println("📝 Создано чанков: ${chunks.size}")

        // 2. Сохраняем метаданные документа
        val fileType = documentProcessor.getFileType(filename)
        val documentId = databaseManager.saveDocument(filename, fileType)

        // 3. Генерируем эмбеддинги и сохраняем чанки
        chunks.forEachIndexed { index, chunk ->
            try {
                // Генерация эмбеддинга через Ollama
                val embedding = ollamaClient.generateEmbedding(chunk)
                println("✨ Сгенерирован эмбеддинг для чанка ${index + 1}/${chunks.size} (размерность: ${embedding.size})")

                // Сохранение в БД
                databaseManager.saveChunk(documentId, chunk, index, embedding)
            } catch (e: Exception) {
                println("❌ Ошибка при обработке чанка $index: ${e.message}")
                // Откатываем документ если хотя бы один чанк не обработался
                databaseManager.deleteDocument(documentId)
                throw e
            }
        }

        println("✅ Документ успешно обработан и сохранен: ID=$documentId, чанков=${chunks.size}")
        return documentId
    }

    /**
     * Генерация эмбеддинга для поискового запроса
     *
     * @param query текст запроса
     * @return вектор эмбеддинга
     */
    suspend fun generateQueryEmbedding(query: String): List<Double> {
        return ollamaClient.generateEmbedding(query)
    }

    /**
     * Получение статистики базы знаний
     */
    fun getKnowledgeBaseStats(): KnowledgeBaseStats {
        val documents = databaseManager.getAllDocuments()
        val chunks = databaseManager.getAllChunks()

        return KnowledgeBaseStats(
            totalDocuments = documents.size,
            totalChunks = chunks.size,
            documents = documents
        )
    }

    /**
     * Удаление документа из базы знаний
     */
    fun deleteDocument(documentId: Int): Boolean {
        return databaseManager.deleteDocument(documentId)
    }
}

/**
 * Статистика базы знаний
 */
data class KnowledgeBaseStats(
    val totalDocuments: Int,
    val totalChunks: Int,
    val documents: List<DocumentData>
)
