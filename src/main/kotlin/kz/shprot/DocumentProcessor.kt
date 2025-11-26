package kz.shprot

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream

/**
 * Процессор для обработки документов: парсинг и чанкирование
 *
 * Поддерживает:
 * - Текстовые файлы (.txt)
 * - PDF файлы (.pdf)
 */
class DocumentProcessor(
    private val chunkSize: Int = 1000,      // Размер чанка в символах
    private val overlap: Int = 200           // Перекрытие между чанками
) {
    /**
     * Обработка файла: парсинг и разбивка на чанки
     *
     * @param fileContent содержимое файла как InputStream
     * @param filename имя файла (для определения типа)
     * @return список текстовых чанков
     */
    fun processFile(fileContent: InputStream, filename: String): List<String> {
        val text = when {
            filename.endsWith(".pdf", ignoreCase = true) -> parsePDF(fileContent)
            filename.endsWith(".txt", ignoreCase = true) -> parseText(fileContent)
            else -> throw IllegalArgumentException("Unsupported file type: $filename")
        }

        return chunkText(text)
    }

    /**
     * Обработка файла: парсинг и разбивка на чанки (для File)
     */
    fun processFile(file: File): List<String> {
        return file.inputStream().use { processFile(it, file.name) }
    }

    /**
     * Парсинг PDF файла в текст
     */
    private fun parsePDF(inputStream: InputStream): String {
        return try {
            val document = Loader.loadPDF(inputStream.readBytes())
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            text
        } catch (e: Exception) {
            println("❌ Ошибка при парсинге PDF: ${e.message}")
            throw e
        }
    }

    /**
     * Парсинг текстового файла
     */
    private fun parseText(inputStream: InputStream): String {
        return inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Разбивка текста на чанки с перекрытием
     *
     * Алгоритм:
     * 1. Разбиваем текст на чанки по chunkSize символов
     * 2. Каждый следующий чанк начинается с overlap символов предыдущего
     * 3. Пытаемся разбивать по границам предложений для лучшего качества
     *
     * @param text текст для разбивки
     * @return список чанков
     */
    fun chunkText(text: String): List<String> {
        if (text.length <= chunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)

            // Пытаемся найти границу предложения для более естественного разбиения
            val actualEnd = if (end < text.length) {
                findSentenceBoundary(text, start, end)
            } else {
                end
            }

            chunks.add(text.substring(start, actualEnd).trim())

            // Следующий чанк начинается с учетом overlap
            start = actualEnd - overlap
            if (start < 0) start = 0

            // Если мы дошли до конца, выходим
            if (actualEnd >= text.length) break
        }

        println("📝 Текст разбит на ${chunks.size} чанков (размер: $chunkSize, overlap: $overlap)")
        return chunks
    }

    /**
     * Поиск границы предложения для естественного разбиения
     *
     * Ищем ближайший символ конца предложения (., !, ?) после позиции end
     * Если не нашли - возвращаем end
     */
    private fun findSentenceBoundary(text: String, start: Int, end: Int): Int {
        // Ищем последний символ конца предложения в пределах 100 символов от end
        val searchStart = maxOf(end - 100, start)
        val searchEnd = minOf(end + 100, text.length)

        val sentenceEnders = listOf('.', '!', '?', '\n')
        var bestBoundary = end

        for (i in (searchStart until searchEnd).reversed()) {
            if (sentenceEnders.contains(text[i])) {
                bestBoundary = i + 1
                break
            }
        }

        return bestBoundary
    }

    /**
     * Получение типа файла
     */
    fun getFileType(filename: String): String {
        return when {
            filename.endsWith(".pdf", ignoreCase = true) -> "pdf"
            filename.endsWith(".txt", ignoreCase = true) -> "text"
            else -> "unknown"
        }
    }
}
