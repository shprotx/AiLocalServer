package kz.shprot.mcp.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модель ответа от Open-Meteo API
 */
@Serializable
data class OpenMeteoResponse(
    @SerialName("current_weather")
    val currentWeather: CurrentWeather
)

/**
 * Текущая погода
 */
@Serializable
data class CurrentWeather(
    val temperature: Double,
    @SerialName("windspeed")
    val windSpeed: Double,
    @SerialName("winddirection")
    val windDirection: Double,
    @SerialName("weathercode")
    val weatherCode: Int,
    @SerialName("is_day")
    val isDay: Int,
    val time: String
)

/**
 * Параметры запроса температуры
 */
data class TemperatureRequest(
    val latitude: Double,
    val longitude: Double
)

/**
 * Результат запроса температуры
 */
@Serializable
data class TemperatureResponse(
    val temperature: Double,
    val unit: String = "C"
)
