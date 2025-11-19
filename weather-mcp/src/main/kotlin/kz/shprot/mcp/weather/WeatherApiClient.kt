package kz.shprot.mcp.weather

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * HTTP клиент для работы с Open-Meteo API
 */
class WeatherApiClient {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val apiUrl = "https://api.open-meteo.com/v1/forecast"
    private val geocodingUrl = "https://geocoding-api.open-meteo.com/v1/search"

    /**
     * Получает текущую температуру для указанных координат
     *
     * @param latitude Широта
     * @param longitude Долгота
     * @return Температура в градусах Цельсия
     */
    suspend fun getTemperature(latitude: Double, longitude: Double): Double {
        logger.info { "Fetching temperature for lat=$latitude, lon=$longitude" }

        return runCatching {
            val response = httpClient.get(apiUrl) {
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("current_weather", true)
            }

            val data: OpenMeteoResponse = response.body()
            val temperature = data.currentWeather.temperature

            logger.info { "Temperature fetched successfully: $temperature°C" }
            temperature
        }.getOrElse { error ->
            logger.error(error) { "Failed to fetch temperature" }
            throw WeatherApiException("Failed to fetch temperature: ${error.message}", error)
        }
    }

    /**
     * Получает полную информацию о текущей погоде
     *
     * @param latitude Широта
     * @param longitude Долгота
     * @return Информация о погоде
     */
    suspend fun getCurrentWeather(latitude: Double, longitude: Double): CurrentWeatherResponse {
        logger.info { "Fetching current weather for lat=$latitude, lon=$longitude" }

        return runCatching {
            val response = httpClient.get(apiUrl) {
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("current_weather", true)
            }

            val data: OpenMeteoResponse = response.body()
            val weather = data.currentWeather

            val weatherDescription = getWeatherDescription(weather.weatherCode)

            CurrentWeatherResponse(
                temperature = weather.temperature,
                windSpeed = weather.windSpeed,
                windDirection = weather.windDirection,
                weatherDescription = weatherDescription,
                isDay = weather.isDay == 1,
                time = weather.time
            )
        }.getOrElse { error ->
            logger.error(error) { "Failed to fetch current weather" }
            throw WeatherApiException("Failed to fetch current weather: ${error.message}", error)
        }
    }

    /**
     * Получает прогноз погоды на несколько дней
     *
     * @param latitude Широта
     * @param longitude Долгота
     * @param days Количество дней (по умолчанию 7)
     * @return Прогноз погоды
     */
    suspend fun getForecast(latitude: Double, longitude: Double, days: Int = 7): WeatherForecastResponse {
        logger.info { "Fetching $days-day forecast for lat=$latitude, lon=$longitude" }

        return runCatching {
            val response = httpClient.get(apiUrl) {
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum,windspeed_10m_max")
                parameter("forecast_days", days)
                parameter("timezone", "auto")
            }

            val data: ForecastResponse = response.body()
            val forecast = data.daily

            val dayForecasts = forecast.time.indices.map { i ->
                DayForecast(
                    date = forecast.time[i],
                    temperatureMax = forecast.temperatureMax[i],
                    temperatureMin = forecast.temperatureMin[i],
                    precipitation = forecast.precipitationSum[i],
                    windSpeed = forecast.windSpeedMax[i]
                )
            }

            WeatherForecastResponse(forecast = dayForecasts)
        }.getOrElse { error ->
            logger.error(error) { "Failed to fetch forecast" }
            throw WeatherApiException("Failed to fetch forecast: ${error.message}", error)
        }
    }

    /**
     * Получает координаты города по его названию
     *
     * @param cityName Название города
     * @return Информация о геолокации
     */
    suspend fun getLocationByCity(cityName: String): GeoLocation {
        logger.info { "Searching for city: $cityName" }

        return runCatching {
            val response = httpClient.get(geocodingUrl) {
                parameter("name", cityName)
                parameter("count", 1)
                parameter("language", "ru")
            }

            val data: GeocodingResponse = response.body()
            val location = data.results?.firstOrNull()
                ?: throw WeatherApiException("City not found: $cityName")

            logger.info { "Found city: ${location.name} (${location.country})" }
            location
        }.getOrElse { error ->
            logger.error(error) { "Failed to find city" }
            throw WeatherApiException("Failed to find city: ${error.message}", error)
        }
    }

    /**
     * Преобразует код погоды в описание
     */
    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "Ясно"
            1, 2, 3 -> "Переменная облачность"
            45, 48 -> "Туман"
            51, 53, 55 -> "Морось"
            61, 63, 65 -> "Дождь"
            71, 73, 75 -> "Снег"
            77 -> "Снежная крупа"
            80, 81, 82 -> "Ливень"
            85, 86 -> "Снегопад"
            95 -> "Гроза"
            96, 99 -> "Гроза с градом"
            else -> "Неизвестно"
        }
    }

    /**
     * Закрывает HTTP клиент
     */
    fun close() {
        httpClient.close()
    }
}

/**
 * Исключение при работе с Weather API
 */
class WeatherApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
