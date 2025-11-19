# Weather MCP Server

Модуль MCP-сервера на Kotlin для получения текущей температуры через Open-Meteo API.

## Описание

Этот модуль реализует MCP (Model Context Protocol) сервер, который предоставляет LLM-агентам инструмент для получения текущей температуры воздуха по координатам.

### Возможности

- ✅ MCP Tool: `get_current_temperature` - получение температуры по координатам (lat, lon)
- ✅ Интеграция с Open-Meteo API (бесплатный, без API ключей)
- ✅ JSON Schema валидация входных параметров
- ✅ Обработка ошибок и логирование
- ✅ Separate HTTP client для API запросов
- ✅ Kotlin idiomatic code (data classes, suspend functions, runCatching)

## Структура проекта

```
weather-mcp/
├── build.gradle.kts              # Gradle конфигурация
├── src/main/kotlin/kz/shprot/mcp/weather/
│   ├── Main.kt                   # Точка входа, MCP Server setup
│   ├── WeatherTool.kt            # Регистрация MCP инструмента
│   ├── WeatherApiClient.kt       # HTTP клиент для Open-Meteo API
│   └── Models.kt                 # Data classes для API и MCP
└── README.md                     # Этот файл
```

## Технологии

- **Kotlin 2.1.20** - язык программирования
- **Kotlin MCP SDK 0.6.0** - официальный SDK для Model Context Protocol
- **Ktor Client 3.0.3** - HTTP клиент для запросов к Open-Meteo API
- **kotlinx.serialization** - JSON сериализация
- **kotlinx-io** - работа с IO streams
- **Okio 3.9.1** - buffering для Source/Sink (для STDIO transport)
- **SLF4J + kotlin-logging** - логирование

## MCP Tool: get_current_temperature

### Input Schema

```json
{
  "type": "object",
  "properties": {
    "latitude": {
      "type": "number",
      "description": "Latitude coordinate (e.g., 55.7558 for Moscow)"
    },
    "longitude": {
      "type": "number",
      "description": "Longitude coordinate (e.g., 37.6173 for Moscow)"
    }
  },
  "required": ["latitude", "longitude"]
}
```

### Output

```json
{
  "temperature": 3.2,
  "unit": "C"
}
```

### Пример вызова

```kotlin
// MCP Tool Call
{
  "name": "get_current_temperature",
  "arguments": {
    "latitude": 55.7558,
    "longitude": 37.6173
  }
}

// Response
{
  "content": [
    {
      "type": "text",
      "text": "{\"temperature\":3.2,\"unit\":\"C\"}"
    }
  ]
}
```

## Компоненты

### 1. Main.kt

Точка входа MCP-сервера:
- Создает `Server` с capabilities (tools support)
- Регистрирует WeatherTool через `registerWeatherTool()`
- Настраивает STDIO transport для общения через stdin/stdout
- Запускает сервер в корутине (`runBlocking`)

### 2. WeatherTool.kt

Регистрация MCP инструмента:
- Создает JSON Schema для входных параметров (Tool.Input)
- Регистрирует handler для обработки запросов
- Извлекает latitude/longitude из request.arguments
- Вызывает WeatherApiClient для получения температуры
- Возвращает `CallToolResult` с JSON ответом или ошибкой

### 3. WeatherApiClient.kt

HTTP клиент для Open-Meteo API:
- Ktor Client с CIO engine и JSON content negotiation
- `suspend fun getTemperature(lat, lon): Double` - получение температуры
- API endpoint: `https://api.open-meteo.com/v1/forecast`
- Обработка ошибок через `runCatching`

### 4. Models.kt

Data классы для работы с API:
- `OpenMeteoResponse` - ответ от Open-Meteo API
- `CurrentWeather` - текущая погода (temperature, windSpeed, etc.)
- `TemperatureRequest` - параметры запроса
- `TemperatureResponse` - результат (temperature + unit)

## Сборка и запуск

### Сборка

```bash
# Из корня проекта
./gradlew :weather-mcp:build

# Создание fat JAR
./gradlew :weather-mcp:shadowJar
```

### Запуск

```bash
# Через Gradle
./gradlew :weather-mcp:run

# Через JAR (после shadowJar)
java -jar weather-mcp/build/libs/weather-mcp-1.0.0.jar
```

## ✅ Решенная проблема: Сервер сразу завершался

### Описание проблемы

После запуска сервер сразу завершался, так как `runBlocking` блок завершался после `server.connect(transport)`.

### Решение

Добавлен вызов `awaitCancellation()` после `server.connect()`, который держит корутину запущенной до отмены:

```kotlin
fun main(): Unit = runBlocking {
    // ... инициализация ...

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
```

**Почему это работает:**
- `awaitCancellation()` приостанавливает корутину до её отмены
- Не потребляет CPU (в отличие от `while(true) { delay(1000) }`)
- Позволяет корректное завершение через shutdown hooks

## Интеграция с основным проектом

### Вариант 1: ProcessBuilder

```kotlin
val process = ProcessBuilder(
    "java", "-jar",
    "/path/to/weather-mcp/build/libs/weather-mcp-1.0.0.jar"
).start()

// Общение через stdin/stdout
val input = process.outputStream  // Пишем в stdin процесса
val output = process.inputStream  // Читаем из stdout процесса
```

### Вариант 2: MCP Client SDK

Использовать Kotlin MCP Client SDK для подключения к серверу:

```kotlin
val client = Client(
    clientInfo = Implementation("ai-local-server", "1.0.0"),
    options = ClientOptions()
)

val transport = StdioClientTransport(
    inputStream = process.inputStream,
    outputStream = process.outputStream
)

client.connect(transport)

// Вызов инструмента
val result = client.callTool(
    name = "get_current_temperature",
    arguments = mapOf(
        "latitude" to 55.7558,
        "longitude" to 37.6173
    )
)
```

## Open-Meteo API

Бесплатный API погоды без требования API ключей.

### Endpoint

```
GET https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current_weather=true
```

### Response Example

```json
{
  "current_weather": {
    "temperature": 3.2,
    "windspeed": 12.5,
    "winddirection": 220,
    "weathercode": 3,
    "is_day": 1,
    "time": "2025-11-19T12:00"
  }
}
```

## Дальнейшие улучшения

- [x] ~~Исправить проблему с STDIO transport~~ (✅ Решено)
- [ ] Добавить больше tools (прогноз на неделю, данные о ветре, etc.)
- [ ] Добавить кэширование API ответов
- [ ] Добавить retry логику при ошибках API
- [ ] Добавить rate limiting
- [ ] Добавить unit тесты
- [ ] Добавить Docker образ для деплоя

## Ссылки

- [Model Context Protocol Specification](https://modelcontextprotocol.io)
- [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Open-Meteo API Documentation](https://open-meteo.com/en/docs)
- [Kotlin MCP SDK Documentation](https://modelcontextprotocol.github.io/kotlin-sdk/)

## Лицензия

Часть проекта AiLocalServer.
