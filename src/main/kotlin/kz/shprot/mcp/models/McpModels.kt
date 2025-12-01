package kz.shprot.mcp.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Описание инструмента (Tool) в MCP протоколе
 */
@Serializable
data class Tool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/**
 * Вызов инструмента от LLM
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

/**
 * Результат выполнения инструмента
 */
@Serializable
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false
)

/**
 * Ответ со списком доступных инструментов
 */
@Serializable
data class ToolsListResponse(
    val tools: List<Tool>
)

/**
 * Запрос на выполнение инструмента
 */
@Serializable
data class ToolExecutionRequest(
    val name: String,
    val arguments: JsonObject
)

/**
 * Ответ с результатом выполнения инструмента
 */
@Serializable
data class ToolExecutionResponse(
    val result: String,
    val isError: Boolean = false
)
