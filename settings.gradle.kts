plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "AiLocalServer"

// Подключаем модуль weather-mcp
include("weather-mcp")