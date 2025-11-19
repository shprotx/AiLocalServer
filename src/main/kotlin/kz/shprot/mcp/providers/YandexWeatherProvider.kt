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
 * Провайдер для работы с Yandex Weather API
 */
class YandexWeatherProvider(
    private val apiKey: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val weatherApiUrl = "https://api.weather.yandex.ru/v2/forecast"
    private val geocoderUrl = "https://nominatim.openstreetmap.org/search"

    /**
     * Получить текущую погоду по названию локации
     */
    suspend fun getCurrentWeather(location: String): Result<SimpleWeatherResponse> = runCatching {
        // 1. Получаем координаты по названию города
        val coords = geocodeLocation(location)
            ?: throw Exception("Не удалось найти координаты для локации: $location")

        // 2. Получаем погоду по координатам
        val weatherResponse: YandexWeatherResponse = client.get(weatherApiUrl) {
            parameter("lat", coords.lat)
            parameter("lon", coords.lon)
            parameter("lang", "ru_RU")
            header("X-Yandex-Weather-Key", apiKey)
        }.body()

        // 3. Преобразуем в упрощенный формат
        SimpleWeatherResponse(
            location = location,
            temperature = weatherResponse.fact.temp,
            feelsLike = weatherResponse.fact.feelsLike,
            condition = translateCondition(weatherResponse.fact.condition),
            humidity = weatherResponse.fact.humidity,
            windSpeed = weatherResponse.fact.windSpeed,
            windDirection = translateWindDirection(weatherResponse.fact.windDir),
            pressure = weatherResponse.fact.pressureMm
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
        val weatherResponse: YandexWeatherResponse = client.get(weatherApiUrl) {
            parameter("lat", coords.lat)
            parameter("lon", coords.lon)
            parameter("lang", "ru_RU")
            parameter("limit", days.coerceIn(1, 7))
            header("X-Yandex-Weather-Key", apiKey)
        }.body()

        // 3. Преобразуем прогнозы
        val forecasts = weatherResponse.forecasts?.take(days)?.map { forecast ->
            DayForecast(
                date = forecast.date,
                dayTemp = forecast.parts.day?.tempAvg,
                nightTemp = forecast.parts.night?.tempAvg,
                condition = forecast.parts.day?.condition?.let { translateCondition(it) },
                humidity = forecast.parts.day?.humidity,
                precipitation = forecast.parts.day?.precMm
            )
        } ?: emptyList()

        SimpleForecastResponse(
            location = location,
            forecasts = forecasts
        )
    }

    /**
     * Геокодирование - получение координат по названию города
     * Использует OpenStreetMap Nominatim (бесплатный, без API ключа)
     */
    private suspend fun geocodeLocation(location: String): Coordinates? = runCatching {
        val response: List<NominatimResponse> = client.get(geocoderUrl) {
            parameter("q", location)
            parameter("format", "json")
            parameter("limit", "1")
            header("User-Agent", "AiLocalServer/1.0")
        }.body()

        response.firstOrNull()?.let {
            Coordinates(it.lat.toDouble(), it.lon.toDouble())
        }
    }.getOrNull()

    /**
     * Перевод кодов погодных условий на русский
     */
    private fun translateCondition(condition: String): String = when (condition) {
        "clear" -> "ясно"
        "partly-cloudy" -> "малооблачно"
        "cloudy" -> "облачно с прояснениями"
        "overcast" -> "пасмурно"
        "light-rain" -> "небольшой дождь"
        "rain" -> "дождь"
        "heavy-rain" -> "сильный дождь"
        "showers" -> "ливень"
        "sleet" -> "дождь со снегом"
        "light-snow" -> "небольшой снег"
        "snow" -> "снег"
        "snow-showers" -> "снегопад"
        "hail" -> "град"
        "thunderstorm" -> "гроза"
        "thunderstorm-with-rain" -> "дождь с грозой"
        "thunderstorm-with-hail" -> "гроза с градом"
        else -> condition
    }

    /**
     * Перевод направлений ветра
     */
    private fun translateWindDirection(dir: String): String = when (dir) {
        "nw" -> "северо-западный"
        "n" -> "северный"
        "ne" -> "северо-восточный"
        "e" -> "восточный"
        "se" -> "юго-восточный"
        "s" -> "южный"
        "sw" -> "юго-западный"
        "w" -> "западный"
        "c" -> "штиль"
        else -> dir
    }

    fun close() {
        client.close()
    }
}

/**
 * Ответ от Nominatim (OpenStreetMap Geocoder)
 */
@kotlinx.serialization.Serializable
data class NominatimResponse(
    val lat: String,
    val lon: String,
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String
)
