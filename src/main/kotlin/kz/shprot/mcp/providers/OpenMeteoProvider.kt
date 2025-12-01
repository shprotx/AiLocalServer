package kz.shprot.mcp.providers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kz.shprot.mcp.models.*

/**
 * Провайдер для работы с Open-Meteo API
 *
 * Open-Meteo - полностью бесплатный weather API без необходимости API ключа!
 * Документация: https://open-meteo.com/en/docs
 */
class OpenMeteoProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val weatherApiUrl = "https://api.open-meteo.com/v1/forecast"
    private val geocodingApiUrl = "https://geocoding-api.open-meteo.com/v1/search"

    /**
     * Получить текущую погоду по названию локации
     */
    suspend fun getCurrentWeather(location: String): Result<SimpleWeatherResponse> = runCatching {
        // 1. Получаем координаты по названию города
        val coords = geocodeLocation(location)
            ?: throw Exception("Не удалось найти координаты для локации: $location")

        // 2. Получаем погоду по координатам
        val weatherResponse: WeatherResponse = client.get(weatherApiUrl) {
            parameter("latitude", coords.lat)
            parameter("longitude", coords.lon)
            parameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure")
            parameter("timezone", "auto")
        }.body()

        val current = weatherResponse.current
            ?: throw Exception("Нет данных о текущей погоде")

        // 3. Преобразуем в упрощенный формат
        SimpleWeatherResponse(
            location = location,
            temperature = current.temperature.toInt(),
            feelsLike = current.apparentTemperature.toInt(),
            condition = getWeatherDescription(current.weatherCode),
            humidity = current.humidity,
            windSpeed = current.windSpeed,
            windDirection = getWindDirection(current.windDirection),
            pressure = current.pressure?.toInt() ?: 0
        )
    }

    /**
     * Получить прогноз погоды на несколько дней
     */
    suspend fun getForecast(location: String, days: Int = 3): Result<SimpleForecastResponse> = runCatching {
        // 1. Получаем координаты
        val coords = geocodeLocation(location)
            ?: throw Exception("Не удалось найти координаты для локации: $location")

        // 2. Получаем прогноз
        val weatherResponse: WeatherResponse = client.get(weatherApiUrl) {
            parameter("latitude", coords.lat)
            parameter("longitude", coords.lon)
            parameter("daily", "temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum,precipitation_probability_max")
            parameter("forecast_days", days.coerceIn(1, 16))
            parameter("timezone", "auto")
        }.body()

        val daily = weatherResponse.daily
            ?: throw Exception("Нет данных о прогнозе погоды")

        // 3. Преобразуем прогнозы
        val forecasts = daily.time.indices.take(days).map { i ->
            DayForecast(
                date = daily.time[i],
                dayTemp = daily.temperatureMax[i].toInt(),
                nightTemp = daily.temperatureMin[i].toInt(),
                condition = getWeatherDescription(daily.weatherCode[i]),
                humidity = null, // Дневная влажность не доступна в базовом API
                precipitation = daily.precipitationSum?.getOrNull(i)
            )
        }

        SimpleForecastResponse(
            location = location,
            forecasts = forecasts
        )
    }

    /**
     * Геокодирование - получение координат по названию города
     * Использует Open-Meteo Geocoding API (бесплатный, без ключа)
     */
    private suspend fun geocodeLocation(location: String): Coordinates? = runCatching {
        val response: GeocodingResponse = client.get(geocodingApiUrl) {
            parameter("name", location)
            parameter("count", "1")
            parameter("language", "ru")
            parameter("format", "json")
        }.body()

        response.results?.firstOrNull()?.let {
            Coordinates(it.latitude, it.longitude)
        }
    }.getOrNull()

    fun close() {
        client.close()
    }
}
