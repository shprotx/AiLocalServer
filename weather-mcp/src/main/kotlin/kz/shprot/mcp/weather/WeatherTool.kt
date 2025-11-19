package kz.shprot.mcp.weather

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

/**
 * Регистрирует инструмент для получения текущей температуры
 *
 * @param server MCP сервер
 * @param weatherClient HTTP клиент для работы с Open-Meteo API
 */
fun registerWeatherTool(server: Server, weatherClient: WeatherApiClient) {
    logger.info { "Registering get_current_temperature tool" }

    server.addTool(
        name = "get_current_temperature",
        description = "Returns the current temperature in Celsius for given coordinates using Open-Meteo API",
        inputSchema = createInputSchema()
    ) { request ->
        logger.info { "Tool called with arguments: ${request.arguments}" }

        runCatching {
            // Извлекаем параметры из запроса
            val latitude = request.arguments["latitude"]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("Missing required parameter: latitude")

            val longitude = request.arguments["longitude"]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("Missing required parameter: longitude")

            logger.info { "Fetching temperature for lat=$latitude, lon=$longitude" }

            // Получаем температуру через API
            val temperature = weatherClient.getTemperature(latitude, longitude)

            // Формируем ответ
            val response = TemperatureResponse(
                temperature = temperature,
                unit = "C"
            )

            val jsonResponse = Json.encodeToString(response)
            logger.info { "Tool execution successful: $jsonResponse" }

            CallToolResult(
                content = listOf(TextContent(jsonResponse))
            )
        }.getOrElse { error ->
            logger.error(error) { "Tool execution failed" }

            val errorMessage = buildJsonObject {
                put("error", error.message ?: "Unknown error")
                put("type", error::class.simpleName ?: "Exception")
            }.toString()

            CallToolResult(
                content = listOf(TextContent(errorMessage)),
                isError = true
            )
        }
    }

    logger.info { "Tool registered successfully" }
}

/**
 * Создает JSON Schema для входных параметров инструмента
 */
private fun createInputSchema(): Tool.Input {
    return Tool.Input(
        properties = buildJsonObject {
            putJsonObject("latitude") {
                put("type", "number")
                put("description", "Latitude coordinate (e.g., 55.7558 for Moscow)")
            }
            putJsonObject("longitude") {
                put("type", "number")
                put("description", "Longitude coordinate (e.g., 37.6173 for Moscow)")
            }
        },
        required = listOf("latitude", "longitude")
    )
}
