package kz.shprot.mcp

import kotlinx.serialization.json.*
import kz.shprot.mcp.models.*
import kz.shprot.mcp.providers.YandexWeatherProvider

/**
 * MCP Server - управление инструментами (tools) и их выполнение
 */
class McpServer(
    private val weatherProvider: YandexWeatherProvider
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Получить список всех доступных инструментов
     */
    fun listTools(): List<Tool> = listOf(
        Tool(
            name = "get_current_weather",
            description = "Получить текущую погоду для указанной локации (город, страна). " +
                    "Возвращает температуру, ощущаемую температуру, условия, влажность, ветер и давление.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("location", buildJsonObject {
                        put("type", "string")
                        put("description", "Название города или локации (например: 'Алматы', 'Москва', 'Paris')")
                    })
                })
                put("required", buildJsonArray {
                    add("location")
                })
            }
        ),
        Tool(
            name = "get_weather_forecast",
            description = "Получить прогноз погоды на несколько дней для указанной локации. " +
                    "Возвращает температуру днем и ночью, условия, влажность и осадки для каждого дня.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("location", buildJsonObject {
                        put("type", "string")
                        put("description", "Название города или локации")
                    })
                    put("days", buildJsonObject {
                        put("type", "integer")
                        put("description", "Количество дней прогноза (от 1 до 7)")
                        put("default", 3)
                    })
                })
                put("required", buildJsonArray {
                    add("location")
                })
            }
        )
    )

    /**
     * Выполнить вызов инструмента
     */
    suspend fun executeTool(name: String, arguments: JsonObject): ToolExecutionResponse {
        return when (name) {
            "get_current_weather" -> executeGetCurrentWeather(arguments)
            "get_weather_forecast" -> executeGetWeatherForecast(arguments)
            else -> ToolExecutionResponse(
                result = "Инструмент '$name' не найден",
                isError = true
            )
        }
    }

    /**
     * Выполнить get_current_weather
     */
    private suspend fun executeGetCurrentWeather(arguments: JsonObject): ToolExecutionResponse {
        return try {
            val location = arguments["location"]?.jsonPrimitive?.content
                ?: return ToolExecutionResponse("Параметр 'location' обязателен", isError = true)

            val result = weatherProvider.getCurrentWeather(location)

            result.fold(
                onSuccess = { weather ->
                    val resultText = """
                        Текущая погода в ${weather.location}:
                        🌡️ Температура: ${weather.temperature}°C (ощущается как ${weather.feelsLike}°C)
                        ☁️ Условия: ${weather.condition}
                        💧 Влажность: ${weather.humidity}%
                        💨 Ветер: ${weather.windDirection}, ${weather.windSpeed} м/с
                        🔽 Давление: ${weather.pressure} мм рт.ст.
                    """.trimIndent()

                    ToolExecutionResponse(result = resultText, isError = false)
                },
                onFailure = { error ->
                    ToolExecutionResponse(
                        result = "Ошибка получения погоды: ${error.message}",
                        isError = true
                    )
                }
            )
        } catch (e: Exception) {
            ToolExecutionResponse(
                result = "Ошибка выполнения: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Выполнить get_weather_forecast
     */
    private suspend fun executeGetWeatherForecast(arguments: JsonObject): ToolExecutionResponse {
        return try {
            val location = arguments["location"]?.jsonPrimitive?.content
                ?: return ToolExecutionResponse("Параметр 'location' обязателен", isError = true)

            val days = arguments["days"]?.jsonPrimitive?.intOrNull ?: 3

            val result = weatherProvider.getForecast(location, days)

            result.fold(
                onSuccess = { forecast ->
                    val forecastText = buildString {
                        appendLine("Прогноз погоды для ${forecast.location} на $days ${getDaysWord(days)}:")
                        appendLine()
                        forecast.forecasts.forEachIndexed { index, day ->
                            appendLine("📅 ${day.date}:")
                            day.dayTemp?.let { appendLine("  🌞 Днем: ${it}°C") }
                            day.nightTemp?.let { appendLine("  🌙 Ночью: ${it}°C") }
                            day.condition?.let { appendLine("  ☁️ Условия: $it") }
                            day.humidity?.let { appendLine("  💧 Влажность: ${it}%") }
                            day.precipitation?.let { appendLine("  🌧️ Осадки: ${it} мм") }
                            if (index < forecast.forecasts.size - 1) appendLine()
                        }
                    }

                    ToolExecutionResponse(result = forecastText, isError = false)
                },
                onFailure = { error ->
                    ToolExecutionResponse(
                        result = "Ошибка получения прогноза: ${error.message}",
                        isError = true
                    )
                }
            )
        } catch (e: Exception) {
            ToolExecutionResponse(
                result = "Ошибка выполнения: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Вспомогательная функция для склонения слова "день"
     */
    private fun getDaysWord(days: Int): String {
        return when {
            days % 10 == 1 && days % 100 != 11 -> "день"
            days % 10 in 2..4 && days % 100 !in 12..14 -> "дня"
            else -> "дней"
        }
    }

    fun close() {
        weatherProvider.close()
    }
}
