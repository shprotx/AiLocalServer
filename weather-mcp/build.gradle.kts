plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.5"
    application
}

group = "kz.shprot.mcp"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Kotlin MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.6.0")

    // Ktor для HTTP и MCP SDK
    val ktorVersion = "3.0.3"
    // Ktor Client для HTTP запросов к Open-Meteo API
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")


    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Kotlinx IO для работы с Source/Sink
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

application {
    mainClass.set("kz.shprot.mcp.weather.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("weather-mcp")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()

    // Исключаем дубликаты метаданных
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

// Настройка зависимостей задач
tasks.named("startScripts") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distTar") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distZip") {
    dependsOn(tasks.shadowJar)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
