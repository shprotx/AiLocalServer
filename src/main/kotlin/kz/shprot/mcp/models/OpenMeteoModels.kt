package kz.shprot.mcp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Open-Meteo API Models
 * Документация: https://open-meteo.com/en/docs
 */

// Геокодирование
@Serializable
data class GeocodingResponse(
    val results: List<GeocodingResult>? = null
)

@Serializable
data class GeocodingResult(
    val id: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("admin1")
    val region: String? = null,
    val timezone: String? = null
)

// Погода
@Serializable
data class WeatherResponse(
    val latitude: Double,
    val longitude: Double,
    @SerialName("current")
    val current: CurrentWeather? = null,
    @SerialName("daily")
    val daily: DailyWeather? = null,
    val timezone: String
)

@Serializable
data class CurrentWeather(
    val time: String,
    @SerialName("temperature_2m")
    val temperature: Double,
    @SerialName("relative_humidity_2m")
    val humidity: Int,
    @SerialName("apparent_temperature")
    val apparentTemperature: Double,
    @SerialName("weather_code")
    val weatherCode: Int,
    @SerialName("wind_speed_10m")
    val windSpeed: Double,
    @SerialName("wind_direction_10m")
    val windDirection: Int,
    @SerialName("surface_pressure")
    val pressure: Double? = null
)

@Serializable
data class DailyWeather(
    val time: List<String>,
    @SerialName("temperature_2m_max")
    val temperatureMax: List<Double>,
    @SerialName("temperature_2m_min")
    val temperatureMin: List<Double>,
    @SerialName("weather_code")
    val weatherCode: List<Int>,
    @SerialName("precipitation_sum")
    val precipitationSum: List<Double>? = null,
    @SerialName("precipitation_probability_max")
    val precipitationProbability: List<Int>? = null
)

/**
 * Коды погодных условий WMO
 * https://open-meteo.com/en/docs
 */
fun getWeatherDescription(code: Int): String = when (code) {
    0 -> "ясно"
    1 -> "преимущественно ясно"
    2 -> "переменная облачность"
    3 -> "облачно"
    45, 48 -> "туман"
    51, 53, 55 -> "морось"
    56, 57 -> "морось с обледенением"
    61, 63, 65 -> "дождь"
    66, 67 -> "ледяной дождь"
    71, 73, 75 -> "снег"
    77 -> "снежные зерна"
    80, 81, 82 -> "ливень"
    85, 86 -> "снегопад"
    95 -> "гроза"
    96, 99 -> "гроза с градом"
    else -> "неизвестно"
}

/**
 * Направление ветра по градусам
 */
fun getWindDirection(degrees: Int): String = when {
    degrees < 23 || degrees >= 338 -> "северный"
    degrees < 68 -> "северо-восточный"
    degrees < 113 -> "восточный"
    degrees < 158 -> "юго-восточный"
    degrees < 203 -> "южный"
    degrees < 248 -> "юго-западный"
    degrees < 293 -> "западный"
    degrees < 338 -> "северо-западный"
    else -> "неизвестно"
}
