package kz.shprot.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Базовый интерфейс для всех инструментов.
 * Следует золотому стандарту: каждый инструмент имеет имя, описание,
 * JSON Schema для параметров и метод выполнения.
 */
interface Tool {
    /** Уникальное имя инструмента (например, "read_file", "write_file") */
    val name: String

    /** Человекочитаемое описание для LLM */
    val description: String

    /** JSON Schema для параметров инструмента */
    val parametersSchema: ToolParametersSchema

    /**
     * Выполняет инструмент с переданными параметрами.
     * @param parameters Параметры в виде JSON объекта
     * @param context Контекст выполнения (текущий проект, рабочая директория и т.д.)
     * @return Результат выполнения
     */
    suspend fun execute(parameters: JsonObject, context: ToolExecutionContext): ToolResult
}

/**
 * JSON Schema для параметров инструмента.
 * Используется LLM для понимания формата входных данных.
 */
@Serializable
data class ToolParametersSchema(
    val type: String = "object",
    val properties: Map<String, ToolParameter> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * Описание одного параметра инструмента.
 */
@Serializable
data class ToolParameter(
    val type: String,  // "string", "integer", "boolean", "array", "object"
    val description: String,
    val enum: List<String>? = null,  // Для параметров с фиксированным набором значений
    val default: JsonElement? = null,
    val items: ToolParameter? = null  // Для массивов
)

/**
 * Контекст выполнения инструмента.
 * Содержит информацию о текущем состоянии (выбранный проект, рабочая директория).
 */
data class ToolExecutionContext(
    /** Корневая директория текущего проекта */
    val projectRoot: String?,
    /** Имя текущего проекта */
    val projectName: String?,
    /** Рабочая директория (может отличаться от корня проекта) */
    val workingDirectory: String,
    /** Дополнительные метаданные */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Результат выполнения инструмента.
 */
@Serializable
sealed class ToolResult {
    /** Успешный результат */
    @Serializable
    data class Success(
        val output: String,
        val metadata: Map<String, String> = emptyMap()
    ) : ToolResult()

    /** Ошибка выполнения */
    @Serializable
    data class Error(
        val message: String,
        val errorCode: String? = null,
        val recoverable: Boolean = true
    ) : ToolResult()
}

/**
 * Расширение для преобразования Tool в формат Function Calling.
 */
fun Tool.toFunctionCallingFormat(): JsonObject {
    return kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive(name))
        put("description", kotlinx.serialization.json.JsonPrimitive(description))
        put("parameters", kotlinx.serialization.json.Json.encodeToJsonElement(
            ToolParametersSchema.serializer(),
            parametersSchema
        ))
    }
}
