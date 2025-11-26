package kz.shprot

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиент для работы с Ollama API (локальные эмбеддинги)
 *
 * Использует модель nomic-embed-text для генерации векторных представлений текста.
 * API endpoint: http://localhost:11434/api/embeddings
 */
class OllamaClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text"
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    /**
     * Генерирует эмбеддинг для текста
     *
     * @param text Текст для генерации эмбеддинга
     * @return Вектор эмбеддинга (список чисел)
     */
    suspend fun generateEmbedding(text: String): List<Double> {
        return runCatching {
            println("🔍 Запрос эмбеддинга для текста (${text.take(50)}...)")
            val response = client.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(OllamaEmbeddingRequest(
                    model = model,
                    prompt = text
                ))
            }

            println("📡 HTTP статус: ${response.status}")
            val rawBody = response.bodyAsText()
            println("📦 Сырой ответ от Ollama (первые 200 символов): ${rawBody.take(200)}")

            // Парсим JSON вручную для лучшей диагностики
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val result = json.decodeFromString<OllamaEmbeddingResponse>(rawBody)

            println("✅ Эмбеддинг сгенерирован (размерность: ${result.embedding.size})")
            result.embedding
        }.getOrElse { e ->
            println("❌ Ошибка генерации эмбеддинга: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Генерирует эмбеддинги для нескольких текстов за один запрос
     *
     * @param texts Список текстов
     * @return Список эмбеддингов
     */
    suspend fun generateEmbeddings(texts: List<String>): List<List<Double>> {
        return texts.map { text ->
            generateEmbedding(text)
        }
    }

    /**
     * Проверяет доступность Ollama сервера
     *
     * @return true если сервер доступен
     */
    suspend fun isAvailable(): Boolean {
        return runCatching {
            client.get("$baseUrl/api/tags")
            true
        }.getOrElse { false }
    }

    fun close() {
        client.close()
    }
}

// Модели для Ollama API
@Serializable
data class OllamaEmbeddingRequest(
    val model: String,
    val prompt: String
)

@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Double>
)
