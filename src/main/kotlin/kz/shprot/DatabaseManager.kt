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
