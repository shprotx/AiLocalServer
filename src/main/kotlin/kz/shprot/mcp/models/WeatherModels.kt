package kz.shprot.mcp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответ от Yandex Weather API
 */
@Serializable
data class YandexWeatherResponse(
    val now: Long,
    @SerialName("now_dt")
    val nowDt: String,
    val info: WeatherInfo,
    val fact: WeatherFact,
    val forecasts: List<WeatherForecast>? = null
)

@Serializable
data class WeatherInfo(
    val lat: Double,
    val lon: Double,
    val url: String? = null,
    @SerialName("def_pressure_mm")
    val defPressureMm: Int? = null,
    @SerialName("def_pressure_pa")
    val defPressurePa: Int? = null
)

@Serializable
data class WeatherFact(
    val temp: Int,
    @SerialName("feels_like")
    val feelsLike: Int,
    val condition: String,
    @SerialName("wind_speed")
    val windSpeed: Double,
    @SerialName("wind_dir")
    val windDir: String,
    @SerialName("pressure_mm")
    val pressureMm: Int,
    @SerialName("pressure_pa")
    val pressurePa: Int,
    val humidity: Int,
    @SerialName("daytime")
    val daytime: String,
    val polar: Boolean,
    val season: String,
    @SerialName("obs_time")
    val obsTime: Long
)

@Serializable
data class WeatherForecast(
    val date: String,
    @SerialName("date_ts")
    val dateTs: Long,
    val week: Int,
    val sunrise: String,
    val sunset: String,
    @SerialName("moon_code")
    val moonCode: Int,
    @SerialName("moon_text")
    val moonText: String,
    val parts: WeatherParts
)

@Serializable
data class WeatherParts(
    val day: WeatherPart? = null,
    val night: WeatherPart? = null,
    val morning: WeatherPart? = null,
    val evening: WeatherPart? = null
)

@Serializable
data class WeatherPart(
    @SerialName("temp_min")
    val tempMin: Int? = null,
    @SerialName("temp_max")
    val tempMax: Int? = null,
    @SerialName("temp_avg")
    val tempAvg: Int? = null,
    @SerialName("feels_like")
    val feelsLike: Int? = null,
    val condition: String? = null,
    @SerialName("wind_speed")
    val windSpeed: Double? = null,
    @SerialName("wind_dir")
    val windDir: String? = null,
    @SerialName("pressure_mm")
    val pressureMm: Int? = null,
    val humidity: Int? = null,
    @SerialName("prec_mm")
    val precMm: Double? = null,
    @SerialName("prec_period")
    val precPeriod: Int? = null,
    @SerialName("prec_prob")
    val precProb: Int? = null
)

/**
 * Упрощенный ответ для агента
 */
@Serializable
data class SimpleWeatherResponse(
    val location: String,
    val temperature: Int,
    val feelsLike: Int,
    val condition: String,
    val humidity: Int,
    val windSpeed: Double,
    val windDirection: String,
    val pressure: Int
)

@Serializable
data class SimpleForecastResponse(
    val location: String,
    val forecasts: List<DayForecast>
)

@Serializable
data class DayForecast(
    val date: String,
    val dayTemp: Int?,
    val nightTemp: Int?,
    val condition: String?,
    val humidity: Int?,
    val precipitation: Double?
)

/**
 * Географические координаты локации
 */
data class Coordinates(val lat: Double, val lon: Double)
