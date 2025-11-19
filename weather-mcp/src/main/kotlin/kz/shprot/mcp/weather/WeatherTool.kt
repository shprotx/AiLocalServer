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
 * Регистрирует все погодные инструменты
 *
 * @param server MCP сервер
 * @param weatherClient HTTP клиент для работы с Open-Meteo API
 */
fun registerWeatherTools(server: Server, weatherClient: WeatherApiClient) {
    registerCurrentTemperatureTool(server, weatherClient)
    registerCurrentWeatherTool(server, weatherClient)
    registerForecastTool(server, weatherClient)
    registerWeatherByCityTool(server, weatherClient)
}

/**
 * Регистрирует инструмент для получения текущей температуры
 */
private fun registerCurrentTemperatureTool(server: Server, weatherClient: WeatherApiClient) {
    logger.info { "Registering get_current_temperature tool" }

    server.addTool(
        name = "get_current_temperature",
        description = "Returns the current temperature in Celsius for given coordinates using Open-Meteo API",
        inputSchema = createCoordinatesSchema()
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
 * Регистрирует инструмент для получения полной информации о текущей погоде
 */
private fun registerCurrentWeatherTool(server: Server, weatherClient: WeatherApiClient) {
    logger.info { "Registering get_current_weather tool" }

    server.addTool(
        name = "get_current_weather",
        description = "Returns full current weather information (temperature, wind, weather description) for given coordinates",
        inputSchema = createCoordinatesSchema()
    ) { request ->
        logger.info { "Tool called with arguments: ${request.arguments}" }

        runCatching {
            val latitude = request.arguments["latitude"]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("Missing required parameter: latitude")
            val longitude = request.arguments["longitude"]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("Missing required parameter: longitude")

            logger.info { "Fetching current weather for lat=$latitude, lon=$longitude" }

            val weather = weatherClient.getCurrentWeather(latitude, longitude)
            val jsonResponse = Json.encodeToString(weather)

            logger.info { "Tool execution successful: $jsonResponse" }

            CallToolResult(content = listOf(TextContent(jsonResponse)))
        }.getOrElse { error ->
            logger.error(error) { "Tool execution failed" }
            CallToolResult(
                content = listOf(TextContent(buildErrorMessage(error))),
                isError = true
            )
        }
    }

    logger.info { "Tool registered successfully" }
}

/**
 * Регистрирует инструмент для получения прогноза погоды
 */
private fun registerForecastTool(server: Server, weatherClient: WeatherApiClient) {
    logger.info { "Registering get_forecast tool" }

    server.addTool(
        name = "get_forecast",
        description = "Returns weather forecast for the next N days (temperature, precipitation, wind) for given coordinates",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("latitude") {
                    put("type", "number")
                    put("description", "Latitude coordinate")
                }
                putJsonObject("longitude") {
                    put("type", "number")
                    put("description", "Longitude coordinate")
                }
                putJsonObject("days") {
                    put("type", "number")
                    put("description", "Number of days to forecast (default: 7, max: 16)")
                }
            },
            required = listOf("latitude", "longitude")
        )
    ) { request ->
        logger.info { "Tool called with arguments: ${request.arguments}" }

        runCatching {
            val latitude = request.arguments["latitude"]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("Missing required parameter: latitude")
            val longitude = request.arguments["longitude"]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("Missing required parameter: longitude")
            val days = request.arguments["days"]?.jsonPrimitive?.int ?: 7

            logger.info { "Fetching $days-day forecast for lat=$latitude, lon=$longitude" }

            val forecast = weatherClient.getForecast(latitude, longitude, days)
            val jsonResponse = Json.encodeToString(forecast)

            logger.info { "Tool execution successful" }

            CallToolResult(content = listOf(TextContent(jsonResponse)))
        }.getOrElse { error ->
            logger.error(error) { "Tool execution failed" }
            CallToolResult(
                content = listOf(TextContent(buildErrorMessage(error))),
                isError = true
            )
        }
    }

    logger.info { "Tool registered successfully" }
}

/**
 * Регистрирует инструмент для получения погоды по названию города
 */
private fun registerWeatherByCityTool(server: Server, weatherClient: WeatherApiClient) {
    logger.info { "Registering get_weather_by_city tool" }

    server.addTool(
        name = "get_weather_by_city",
        description = "Returns current weather for a city by its name. Automatically finds city coordinates and returns full weather information",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name (e.g., 'Москва', 'Санкт-Петербург', 'London')")
                }
            },
            required = listOf("city")
        )
    ) { request ->
        logger.info { "Tool called with arguments: ${request.arguments}" }

        runCatching {
            val city = request.arguments["city"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required parameter: city")

            logger.info { "Finding location and weather for city: $city" }

            // Находим координаты города
            val location = weatherClient.getLocationByCity(city)

            // Получаем погоду для найденных координат
            val weather = weatherClient.getCurrentWeather(location.latitude, location.longitude)

            // Объединяем в один ответ
            val response = buildJsonObject {
                put("city", location.name)
                location.country?.let { put("country", it) }
                location.region?.let { put("region", it) }
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("temperature", weather.temperature)
                put("windSpeed", weather.windSpeed)
                put("windDirection", weather.windDirection)
                put("weatherDescription", weather.weatherDescription)
                put("isDay", weather.isDay)
                put("time", weather.time)
            }.toString()

            logger.info { "Tool execution successful: $response" }

            CallToolResult(content = listOf(TextContent(response)))
        }.getOrElse { error ->
            logger.error(error) { "Tool execution failed" }
            CallToolResult(
                content = listOf(TextContent(buildErrorMessage(error))),
                isError = true
            )
        }
    }

    logger.info { "Tool registered successfully" }
}

/**
 * Создает JSON Schema для координат
 */
private fun createCoordinatesSchema(): Tool.Input {
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

/**
 * Создает сообщение об ошибке в JSON формате
 */
private fun buildErrorMessage(error: Throwable): String {
    return buildJsonObject {
        put("error", error.message ?: "Unknown error")
        put("type", error::class.simpleName ?: "Exception")
    }.toString()
}
