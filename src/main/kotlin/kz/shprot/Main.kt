package kz.shprot

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Локальный сервер для общения с Yandex LLM ===")
    println()

    val apiKey = System.getenv("YANDEX_API_KEY")
    val folderId = System.getenv("YANDEX_FOLDER_ID")

    if (apiKey.isNullOrBlank() || folderId.isNullOrBlank()) {
        println("Ошибка: Необходимо установить переменные окружения:")
        println("  - YANDEX_API_KEY (ваш API ключ)")
        println("  - YANDEX_FOLDER_ID (ID вашей папки в Yandex Cloud)")
        return@runBlocking
    }

    val client = YandexLLMClient(apiKey, folderId)

    println("Сервер запущен. Введите ваше сообщение (или 'exit' для выхода):")
    println()

    while (true) {
        print("Вы: ")
        val userInput = readlnOrNull()?.trim()

        if (userInput.isNullOrBlank()) {
            continue
        }

        if (userInput.lowercase() == "exit") {
            println("Завершение работы...")
            break
        }

        println("Отправка запроса...")
        val response = client.sendMessage(userInput)
        println()
        println("Ассистент: $response")
        println()
    }

    client.close()
    println("Сервер остановлен.")
}
