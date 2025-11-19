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
