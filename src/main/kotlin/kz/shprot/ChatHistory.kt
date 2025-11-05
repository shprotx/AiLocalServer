package kz.shprot

import kz.shprot.models.Message
import java.util.concurrent.ConcurrentHashMap

class ChatHistory {
    private val sessions = ConcurrentHashMap<String, MutableList<Message>>()

    fun getSystemPrompt(): String {
        return """Ты - полезный AI-ассистент.
            |Ты должен ВСЕГДА отвечать СТРОГО в формате JSON.
            |Формат ответа: {"title":"краткий заголовок ответа","message":"полный текст ответа"}
            |Не добавляй никаких дополнительных символов до или после JSON.
            |Пример правильного ответа:
            |{"title":"Ответ на вопрос","message":"Здесь идет полный текст ответа с подробностями"}
        """.trimMargin()
    }

    fun addMessage(sessionId: String, role: String, text: String) {
        val messages = sessions.getOrPut(sessionId) { mutableListOf() }
        messages.add(Message(role = role, text = text))
    }

    fun getMessages(sessionId: String): List<Message> {
        return sessions[sessionId]?.toList() ?: emptyList()
    }

    fun buildMessagesWithHistory(sessionId: String, userMessage: String): List<Message> {
        val messages = mutableListOf<Message>()

        // Добавляем system prompt
        messages.add(Message(role = "system", text = getSystemPrompt()))

        // Добавляем историю
        messages.addAll(getMessages(sessionId))

        // Добавляем текущее сообщение пользователя
        messages.add(Message(role = "user", text = userMessage))

        return messages
    }

    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}
