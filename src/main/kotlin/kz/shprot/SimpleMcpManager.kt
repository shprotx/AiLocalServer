package kz.shprot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Упрощенный MCP менеджер - общается с серверами напрямую через JSON-RPC
 * Не требует MCP Client SDK, работает с любыми MCP серверами
 */
class SimpleMcpManager(private val configPath: String = "mcp-servers.json") {
    private val logger = LoggerFactory.getLogger(SimpleMcpManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ID для JSON-RPC запросов
    private val requestId = AtomicLong(1)

    // Карта: название сервера -> процесс и потоки
    private val servers = mutableMapOf<String, ServerProcess>()

    // Карта: название инструмента -> название сервера
    private val toolToServer = mutableMapOf<String, String>()

    // Карта: название инструмента -> схема инструмента (для Function Calling)
    private val toolSchemas = mutableMapOf<String, JsonObject>()

    data class ServerProcess(
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val config: McpServerConfig
    )

    @Serializable
    data class McpServerConfig(
        val type: String,
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String>? = null,
        val description: String? = null
    )

    @Serializable
    data class McpServersConfig(
        val mcpServers: Map<String, McpServerConfig>
    )

    /**
     * Запускает все MCP серверы
     */
    suspend fun startAllServers() {
        logger.info("🚀 Starting MCP servers from: $configPath")

        val config = readConfig()

        config.mcpServers.forEach { (name, serverConfig) ->
            try {
                startServer(name, serverConfig)
            } catch (e: Exception) {
                logger.error("❌ Failed to start '$name': ${e.message}", e)
            }
        }

        logger.info("✅ Started ${servers.size} MCP servers")
    }

    /**
     * Запускает один сервер
     */
    private suspend fun startServer(name: String, config: McpServerConfig) {
        logger.info("🔌 Starting MCP server '$name'...")
        logger.info("   Command: ${config.command} ${config.args.joinToString(" ")}")

        val process = withContext(Dispatchers.IO) {
            val processBuilder = ProcessBuilder(listOf(config.command) + config.args)

            // Добавляем переменные окружения, если указаны
            config.env?.let { envVars ->
                processBuilder.environment().putAll(envVars)
                logger.info("   Environment variables: ${envVars.keys.joinToString(", ")}")
            }

            processBuilder.start()
        }

        logger.info("   PID: ${process.pid()}")

        val writer = process.outputStream.bufferedWriter()
        val reader = process.inputStream.bufferedReader()

        // Сохраняем
        servers[name] = ServerProcess(process, writer, reader, config)

        // Инициализируем соединение
        initializeServer(name)

        logger.info("✅ Server '$name' ready")
    }

    /**
     * Инициализирует MCP соединение
     */
    private suspend fun initializeServer(serverName: String) {
        val server = servers[serverName]!!

        // Отправляем initialize
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.getAndIncrement())
            put("method", "initialize")
            putJsonObject("params") {
                putJsonObject("clientInfo") {
                    put("name", "ai-local-server")
                    put("version", "1.0.0")
                }
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {
                    putJsonObject("roots") {
                        put("listChanged", true)
                    }
                }
            }
        }

        sendRequest(serverName, initRequest)
        val initResponse = readResponse(serverName)
        logger.info("   Initialize response: ${initResponse.toString().take(100)}...")

        // Отправляем initialized notification
        val initializedNotif = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        sendRequest(serverName, initializedNotif)

        // Получаем список инструментов
        val toolsRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.getAndIncrement())
            put("method", "tools/list")
        }

        sendRequest(serverName, toolsRequest)
        val toolsResponse = readResponse(serverName)

        val tools = toolsResponse["result"]?.jsonObject?.get("tools")?.jsonArray ?: emptyList()
        tools.forEach { tool ->
            val toolObj = tool.jsonObject
            val toolName = toolObj["name"]?.jsonPrimitive?.content!!
            toolToServer[toolName] = serverName
            toolSchemas[toolName] = toolObj // Сохраняем полную схему
            logger.info("   📋 Tool: $toolName")
        }
    }

    /**
     * Вызывает MCP инструмент
     */
    suspend fun callTool(toolName: String, arguments: Map<String, Any>): String {
        val serverName = toolToServer[toolName]
            ?: throw IllegalArgumentException("Tool '$toolName' not found")

        logger.info("🔧 [MCP:$serverName] Calling '$toolName' with args: $arguments")

        val startTime = System.currentTimeMillis()

        // Формируем запрос
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.getAndIncrement())
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", toolName)
                putJsonObject("arguments") {
                    arguments.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            is Long -> put(k, v)
                            is Double -> put(k, v)
                            is Float -> put(k, v.toDouble())
                            is Number -> {
                                // Для других типов Number пробуем определить, целое или дробное
                                val doubleVal = v.toDouble()
                                if (doubleVal % 1.0 == 0.0) {
                                    put(k, v.toLong())
                                } else {
                                    put(k, doubleVal)
                                }
                            }
                            is String -> put(k, v)
                            is Boolean -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }
            }
        }

        sendRequest(serverName, request)
        val response = readResponse(serverName)

        val duration = System.currentTimeMillis() - startTime

        // Извлекаем результат
        val result = response["result"]?.jsonObject
        val content = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
        val text = content?.get("text")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Empty response from tool")

        logger.info("📦 [MCP:$serverName] Result: $text (${duration}ms)")

        return text
    }

    /**
     * Отправляет JSON-RPC запрос
     */
    private suspend fun sendRequest(serverName: String, request: JsonObject) = withContext(Dispatchers.IO) {
        val server = servers[serverName]!!
        val requestStr = request.toString()

        server.writer.write(requestStr)
        server.writer.newLine()
        server.writer.flush()
    }

    /**
     * Читает JSON-RPC ответ
     */
    private suspend fun readResponse(serverName: String): JsonObject = withContext(Dispatchers.IO) {
        val server = servers[serverName]!!
        val line = server.reader.readLine()
            ?: throw IOException("Server closed connection")

        json.parseToJsonElement(line).jsonObject
    }

    /**
     * Получает список инструментов в формате для Yandex Function Calling
     */
    fun getToolsForFunctionCalling(): List<JsonObject> {
        return toolSchemas.map { (toolName, schema) ->
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", toolName)
                    put("description", schema["description"]?.jsonPrimitive?.content ?: "MCP tool: $toolName")

                    // Копируем inputSchema как parameters
                    val inputSchema = schema["inputSchema"]?.jsonObject
                    if (inputSchema != null) {
                        put("parameters", inputSchema)
                    } else {
                        // Fallback - пустая схема
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                    }
                }
            }
        }
    }

    /**
     * Получает детальную информацию о всех инструментах для MCP Orchestrator
     */
    fun listAllToolsDetailed(): List<ToolInfo> {
        return toolSchemas.map { (toolName, schema) ->
            val description = schema["description"]?.jsonPrimitive?.content ?: "MCP tool"
            val inputSchema = schema["inputSchema"]?.jsonObject
            val properties = inputSchema?.get("properties")?.jsonObject

            val parametersDescription = if (properties != null && properties.isNotEmpty()) {
                properties.keys.joinToString(", ") { key ->
                    val prop = properties[key]?.jsonObject
                    val type = prop?.get("type")?.jsonPrimitive?.content ?: "any"
                    val desc = prop?.get("description")?.jsonPrimitive?.content ?: ""
                    "$key: $type${if (desc.isNotEmpty()) " - $desc" else ""}"
                }
            } else {
                // Fallback: используем сырую схему как строку для отладки
                val schemaStr = inputSchema?.toString() ?: "no schema"
                logger.debug("Tool $toolName has no properties. Schema: $schemaStr")

                // Для инструментов без явных properties, пытаемся извлечь информацию из описания
                // или показываем что параметры определяются динамически
                if (inputSchema != null) {
                    "параметры определяются схемой (см. документацию MCP сервера)"
                } else {
                    "параметры не определены"
                }
            }

            ToolInfo(
                name = toolName,
                description = description,
                parameters = parametersDescription,
                serverName = toolToServer[toolName] ?: "unknown"
            )
        }
    }

    data class ToolInfo(
        val name: String,
        val description: String,
        val parameters: String,
        val serverName: String
    )

    /**
     * Останавливает все серверы
     */
    suspend fun stopAllServers() {
        logger.info("🛑 Stopping all MCP servers...")

        servers.forEach { (name, server) ->
            try {
                server.process.destroy()
                withContext(Dispatchers.IO) {
                    server.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                }

                if (server.process.isAlive) {
                    server.process.destroyForcibly()
                }

                logger.info("   ✅ '$name' stopped")
            } catch (e: Exception) {
                logger.error("   ❌ Error stopping '$name': ${e.message}")
            }
        }

        servers.clear()
        toolToServer.clear()
    }

    private fun readConfig(): McpServersConfig {
        val file = File(configPath)
        if (!file.exists()) {
            logger.warn("Config not found, creating empty")
            return McpServersConfig(emptyMap())
        }

        return json.decodeFromString(McpServersConfig.serializer(), file.readText())
    }
}
