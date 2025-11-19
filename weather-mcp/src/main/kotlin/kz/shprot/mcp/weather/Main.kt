package kz.shprot.mcp.weather

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import okio.sink

private val logger = KotlinLogging.logger {}

/**
 * Точка входа MCP-сервера для получения погоды
 */
fun main() = runBlocking {
    logger.info { "Starting Weather MCP Server..." }

    // Создаем HTTP клиент для Open-Meteo API
    val weatherClient = WeatherApiClient()

    // Создаем MCP сервер
    val server = createMcpServer()

    // Регистрируем инструмент для получения температуры
    registerWeatherTool(server, weatherClient)

    logger.info { "Server configured, starting STDIO transport..." }

    // Создаем STDIO transport с Okio
    val transport = StdioServerTransport(
        inputStream = System.`in`.source().buffer(),
        outputStream = System.out.sink().buffer()
    )

    // Подключаем сервер к транспорту и запускаем
    try {
        server.connect(transport)
        logger.info { "Weather MCP Server started successfully" }
    } catch (e: Exception) {
        logger.error(e) { "Failed to start server" }
        weatherClient.close()
        throw e
    }

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Weather MCP Server..." }
        weatherClient.close()
    })
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
