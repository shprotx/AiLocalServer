package kz.shprot.mcp.weather

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered

private val logger = KotlinLogging.logger {}

/**
 * Точка входа MCP-сервера для получения погоды
 */
fun main(): Unit = runBlocking {
    logger.info { "Starting Weather MCP Server..." }

    // Создаем HTTP клиент для Open-Meteo API
    val weatherClient = WeatherApiClient()

    // Создаем MCP сервер
    val server = createMcpServer()

    // Регистрируем все погодные инструменты
    registerWeatherTools(server, weatherClient)

    logger.info { "Server configured, starting STDIO transport..." }

    // Создаем STDIO transport с kotlinx-io
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    // Подключаем сервер к транспорту и запускаем
    try {
        server.connect(transport)
        logger.info { "Weather MCP Server started successfully" }

        // Держим сервер запущенным до отмены корутины
        awaitCancellation()
    } catch (e: Exception) {
        logger.error(e) { "Failed to start server" }
        weatherClient.close()
        throw e
    } finally {
        // Graceful shutdown
        logger.info { "Shutting down Weather MCP Server..." }
        weatherClient.close()
    }
}

/**
 * Создает и настраивает MCP сервер
 */
private fun createMcpServer(): Server {
    return Server(
        serverInfo = Implementation(
            name = "weather-mcp-server",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(
                    listChanged = true
                )
            )
        )
    )
}
