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

/**
 * Полная информация о текущей погоде
 */
@Serializable
data class CurrentWeatherResponse(
    val temperature: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val weatherDescription: String,
    val isDay: Boolean,
    val time: String
)

/**
 * Ответ от API прогноза погоды
 */
@Serializable
data class ForecastResponse(
    val daily: DailyForecast
)

/**
 * Дневной прогноз
 */
@Serializable
data class DailyForecast(
    val time: List<String>,
    @SerialName("temperature_2m_max")
    val temperatureMax: List<Double>,
    @SerialName("temperature_2m_min")
    val temperatureMin: List<Double>,
    @SerialName("precipitation_sum")
    val precipitationSum: List<Double>,
    @SerialName("windspeed_10m_max")
    val windSpeedMax: List<Double>
)

/**
 * Результат запроса прогноза
 */
@Serializable
data class WeatherForecastResponse(
    val forecast: List<DayForecast>
)

/**
 * Прогноз на один день
 */
@Serializable
data class DayForecast(
    val date: String,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val precipitation: Double,
    val windSpeed: Double
)

/**
 * Ответ от Geocoding API
 */
@Serializable
data class GeocodingResponse(
    val results: List<GeoLocation>? = null
)

/**
 * Геолокация города
 */
@Serializable
data class GeoLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("admin1")
    val region: String? = null
)
