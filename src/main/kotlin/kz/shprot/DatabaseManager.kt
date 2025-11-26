package kz.shprot

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Менеджер базы данных SQLite для хранения чатов и сообщений
 */
class DatabaseManager(private val dbPath: String = "chats.db") {
    private var connection: Connection? = null

    init {
        initDatabase()
    }

    /**
     * Инициализация базы данных и создание таблиц
     */
    private fun initDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

        // Создание таблицы чатов
        connection?.createStatement()?.execute("""
            CREATE TABLE IF NOT EXISTS chats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Создание таблицы сообщений
        connection?.createStatement()?.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )
        """)

        // Создание индекса для быстрого поиска сообщений по chat_id
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_messages_chat_id ON messages(chat_id)
        """)

        // Создание таблицы документов (для RAG / база знаний)
        connection?.createStatement()?.execute("""
            CREATE TABLE IF NOT EXISTS documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                filename TEXT NOT NULL,
                file_type TEXT NOT NULL,
                upload_date INTEGER NOT NULL,
                total_chunks INTEGER NOT NULL DEFAULT 0
            )
        """)

        // Создание таблицы чанков текста с эмбеддингами
        connection?.createStatement()?.execute("""
            CREATE TABLE IF NOT EXISTS chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                content TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                embedding TEXT NOT NULL,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
        """)

        // Индекс для быстрого поиска чанков по document_id
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON chunks(document_id)
        """)

        println("✅ База данных инициализирована: $dbPath")
    }

    /**
     * Создание нового чата
     * @param title название чата
     * @return ID созданного чата
     */
    fun createChat(title: String): Int {
        val currentTime = System.currentTimeMillis()
        val statement = connection?.prepareStatement(
            "INSERT INTO chats (title, created_at, updated_at) VALUES (?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ) ?: throw IllegalStateException("Database connection is null")

        statement.setString(1, title)
        statement.setLong(2, currentTime)
        statement.setLong(3, currentTime)
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        return if (generatedKeys.next()) {
            val chatId = generatedKeys.getInt(1)
            println("✅ Создан новый чат: ID=$chatId, title='$title'")
            chatId
        } else {
            throw IllegalStateException("Failed to create chat")
        }
    }

    /**
     * Создание нового чата с конкретным ID (для системных чатов)
     * @param id желаемый ID чата
     * @param title название чата
     * @return true если успешно создан
     */
    fun createChatWithId(id: Int, title: String): Boolean = runCatching {
        // Проверяем, существует ли чат с таким ID
        val existingChat = getChat(id)
        if (existingChat != null) {
            println("⚠️ Чат с ID=$id уже существует")
            return false
        }

        val currentTime = System.currentTimeMillis()
        val statement = connection?.prepareStatement(
            "INSERT INTO chats (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)"
        ) ?: throw IllegalStateException("Database connection is null")

        statement.setInt(1, id)
        statement.setString(2, title)
        statement.setLong(3, currentTime)
        statement.setLong(4, currentTime)
        statement.executeUpdate()

        println("✅ Создан системный чат: ID=$id, title='$title'")
        true
    }.getOrElse { e ->
        println("❌ Ошибка при создании чата с ID=$id: ${e.message}")
        false
    }

    /**
     * Получение списка всех чатов
     * @return список чатов, отсортированных по времени обновления (новые первые)
     */
    fun getAllChats(): List<ChatData> {
        val chats = mutableListOf<ChatData>()
        val statement = connection?.createStatement() ?: return chats
        val resultSet = statement.executeQuery(
            "SELECT id, title, created_at, updated_at FROM chats ORDER BY updated_at DESC"
        )

        while (resultSet.next()) {
            chats.add(resultSet.toChatData())
        }

        println("📋 Загружено чатов: ${chats.size}")
        return chats
    }

    /**
     * Получение чата по ID
     */
    fun getChat(chatId: Int): ChatData? {
        val statement = connection?.prepareStatement(
            "SELECT id, title, created_at, updated_at FROM chats WHERE id = ?"
        ) ?: return null

        statement.setInt(1, chatId)
        val resultSet = statement.executeQuery()

        return if (resultSet.next()) {
            resultSet.toChatData()
        } else {
            null
        }
    }

    /**
     * Удаление чата и всех его сообщений
     */
    fun deleteChat(chatId: Int): Boolean = runCatching {
        val statement = connection?.prepareStatement("DELETE FROM chats WHERE id = ?")
            ?: throw IllegalStateException("Database connection is null")

        statement.setInt(1, chatId)
        val deleted = statement.executeUpdate() > 0

        if (deleted) {
            println("🗑️ Удален чат: ID=$chatId")
        }
        deleted
    }.getOrElse { e ->
        println("❌ Ошибка при удалении чата $chatId: ${e.message}")
        false
    }

    /**
     * Сохранение сообщения в чат
     */
    fun saveMessage(chatId: Int, role: String, content: String): Boolean = runCatching {
        val statement = connection?.prepareStatement(
            "INSERT INTO messages (chat_id, role, content, timestamp) VALUES (?, ?, ?, ?)"
        ) ?: throw IllegalStateException("Database connection is null")

        statement.setInt(1, chatId)
        statement.setString(2, role)
        statement.setString(3, content)
        statement.setLong(4, System.currentTimeMillis())
        statement.executeUpdate()

        // Обновляем время последнего обновления чата
        updateChatTimestamp(chatId)

        true
    }.getOrElse { e ->
        println("❌ Ошибка при сохранении сообщения: ${e.message}")
        false
    }

    /**
     * Получение всех сообщений чата
     */
    fun getMessages(chatId: Int): List<MessageData> {
        val messages = mutableListOf<MessageData>()
        val statement = connection?.prepareStatement(
            "SELECT id, chat_id, role, content, timestamp FROM messages WHERE chat_id = ? ORDER BY timestamp ASC"
        ) ?: return messages

        statement.setInt(1, chatId)
        val resultSet = statement.executeQuery()

        while (resultSet.next()) {
            messages.add(resultSet.toMessageData())
        }

        println("💬 Загружено сообщений для чата $chatId: ${messages.size}")
        return messages
    }

    /**
     * Обновление времени последнего обновления чата
     */
    fun updateChatTimestamp(chatId: Int) {
        val statement = connection?.prepareStatement(
            "UPDATE chats SET updated_at = ? WHERE id = ?"
        ) ?: return

        statement.setLong(1, System.currentTimeMillis())
        statement.setInt(2, chatId)
        statement.executeUpdate()
    }

    /**
     * Обновление заголовка чата
     */
    fun updateChatTitle(chatId: Int, title: String): Boolean = runCatching {
        val statement = connection?.prepareStatement(
            "UPDATE chats SET title = ? WHERE id = ?"
        ) ?: throw IllegalStateException("Database connection is null")

        statement.setString(1, title)
        statement.setInt(2, chatId)
        statement.executeUpdate() > 0
    }.getOrElse { false }

    // ==================== RAG / Knowledge Base Methods ====================

    /**
     * Сохранение документа в базу знаний
     * @return ID созданного документа
     */
    fun saveDocument(filename: String, fileType: String): Int {
        val statement = connection?.prepareStatement(
            "INSERT INTO documents (filename, file_type, upload_date, total_chunks) VALUES (?, ?, ?, 0)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ) ?: throw IllegalStateException("Database connection is null")

        statement.setString(1, filename)
        statement.setString(2, fileType)
        statement.setLong(3, System.currentTimeMillis())
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        return if (generatedKeys.next()) {
            val docId = generatedKeys.getInt(1)
            println("📄 Документ сохранен: ID=$docId, filename='$filename'")
            docId
        } else {
            throw IllegalStateException("Failed to save document")
        }
    }

    /**
     * Сохранение чанка текста с эмбеддингом
     */
    fun saveChunk(documentId: Int, content: String, chunkIndex: Int, embedding: List<Double>): Boolean = runCatching {
        val embeddingJson = embedding.joinToString(",", "[", "]")
        val statement = connection?.prepareStatement(
            "INSERT INTO chunks (document_id, content, chunk_index, embedding) VALUES (?, ?, ?, ?)"
        ) ?: throw IllegalStateException("Database connection is null")

        statement.setInt(1, documentId)
        statement.setString(2, content)
        statement.setInt(3, chunkIndex)
        statement.setString(4, embeddingJson)
        statement.executeUpdate()

        // Обновляем счетчик чанков в документе
        updateDocumentChunkCount(documentId)

        true
    }.getOrElse { e ->
        println("❌ Ошибка при сохранении чанка: ${e.message}")
        false
    }

    /**
     * Обновление счетчика чанков в документе
     */
    private fun updateDocumentChunkCount(documentId: Int) {
        val statement = connection?.prepareStatement(
            "UPDATE documents SET total_chunks = (SELECT COUNT(*) FROM chunks WHERE document_id = ?) WHERE id = ?"
        ) ?: return

        statement.setInt(1, documentId)
        statement.setInt(2, documentId)
        statement.executeUpdate()
    }

    /**
     * Получение всех чанков из базы знаний
     */
    fun getAllChunks(): List<ChunkData> {
        val chunks = mutableListOf<ChunkData>()
        val statement = connection?.createStatement() ?: return chunks
        val resultSet = statement.executeQuery(
            "SELECT id, document_id, content, chunk_index, embedding FROM chunks ORDER BY document_id, chunk_index"
        )

        while (resultSet.next()) {
            chunks.add(resultSet.toChunkData())
        }

        println("📚 Загружено чанков из базы знаний: ${chunks.size}")
        return chunks
    }

    /**
     * Получение всех документов
     */
    fun getAllDocuments(): List<DocumentData> {
        val documents = mutableListOf<DocumentData>()
        val statement = connection?.createStatement() ?: return documents
        val resultSet = statement.executeQuery(
            "SELECT id, filename, file_type, upload_date, total_chunks FROM documents ORDER BY upload_date DESC"
        )

        while (resultSet.next()) {
            documents.add(resultSet.toDocumentData())
        }

        println("📄 Загружено документов: ${documents.size}")
        return documents
    }

    /**
     * Удаление документа и всех его чанков
     */
    fun deleteDocument(documentId: Int): Boolean = runCatching {
        val statement = connection?.prepareStatement("DELETE FROM documents WHERE id = ?")
            ?: throw IllegalStateException("Database connection is null")

        statement.setInt(1, documentId)
        val deleted = statement.executeUpdate() > 0

        if (deleted) {
            println("🗑️ Удален документ: ID=$documentId")
        }
        deleted
    }.getOrElse { e ->
        println("❌ Ошибка при удалении документа $documentId: ${e.message}")
        false
    }

    /**
     * Полная очистка базы знаний (все документы и чанки)
     */
    fun clearKnowledgeBase(): Boolean = runCatching {
        connection?.createStatement()?.use { statement ->
            // Сначала удаляем все чанки
            val chunksDeleted = statement.executeUpdate("DELETE FROM chunks")
            println("🗑️ Удалено чанков: $chunksDeleted")

            // Затем удаляем все документы
            val docsDeleted = statement.executeUpdate("DELETE FROM documents")
            println("🗑️ Удалено документов: $docsDeleted")

            // Сбрасываем счетчик автоинкремента для SQLite
            statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name='documents'")
            statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name='chunks'")
            println("✅ База знаний полностью очищена")
        }
        true
    }.getOrElse { e ->
        println("❌ Ошибка при очистке базы знаний: ${e.message}")
        e.printStackTrace()
        false
    }

    /**
     * Закрытие соединения с БД
     */
    fun close() {
        connection?.close()
        println("🔌 Соединение с БД закрыто")
    }

    // Extension functions для маппинга ResultSet -> Data classes
    private fun ResultSet.toChatData() = ChatData(
        id = getInt("id"),
        title = getString("title"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at")
    )

    private fun ResultSet.toMessageData() = MessageData(
        id = getInt("id"),
        chatId = getInt("chat_id"),
        role = getString("role"),
        content = getString("content"),
        timestamp = getLong("timestamp")
    )

    private fun ResultSet.toChunkData() = ChunkData(
        id = getInt("id"),
        documentId = getInt("document_id"),
        content = getString("content"),
        chunkIndex = getInt("chunk_index"),
        embedding = parseEmbedding(getString("embedding"))
    )

    private fun ResultSet.toDocumentData() = DocumentData(
        id = getInt("id"),
        filename = getString("filename"),
        fileType = getString("file_type"),
        uploadDate = getLong("upload_date"),
        totalChunks = getInt("total_chunks")
    )

    /**
     * Парсинг эмбеддинга из JSON строки
     */
    private fun parseEmbedding(json: String): List<Double> {
        return json.trim('[', ']')
            .split(",")
            .map { it.toDouble() }
    }
}

/**
 * Модель данных для чата
 */
data class ChatData(
    val id: Int,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Модель данных для сообщения
 */
data class MessageData(
    val id: Int,
    val chatId: Int,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * Модель данных для документа в базе знаний
 */
data class DocumentData(
    val id: Int,
    val filename: String,
    val fileType: String,
    val uploadDate: Long,
    val totalChunks: Int
)

/**
 * Модель данных для чанка текста с эмбеддингом
 */
data class ChunkData(
    val id: Int,
    val documentId: Int,
    val content: String,
    val chunkIndex: Int,
    val embedding: List<Double>
)
