# AI Local Server

Локальный сервер для общения с облачными LLM-моделями. В данный момент поддерживает Yandex GPT API.

## Требования

- JDK 21
- Yandex Cloud аккаунт с настроенным API ключом

## Настройка

1. Получите API ключ и Folder ID в Yandex Cloud:
   - Создайте сервисный аккаунт
   - Назначьте роль `ai.languageModels.user`
   - Создайте API ключ
   - Скопируйте Folder ID вашего облака

2. Установите переменные окружения:
```bash
export YANDEX_API_KEY=your_api_key
export YANDEX_FOLDER_ID=your_folder_id
export MODEL_TYPE=yandexgpt  # или yandexgpt-lite (опционально, по умолчанию yandexgpt)
```

### Выбор модели

- **yandexgpt** (рекомендуется) - полная модель с поддержкой JSON Schema, более точные ответы
- **yandexgpt-lite** - облегченная версия, быстрее и дешевле, но без JSON Schema

## Запуск

```bash
./gradlew run
```

## Использование

После запуска введите ваше сообщение в терминал и нажмите Enter. Сервер отправит запрос к Yandex GPT и выведет ответ.

Для выхода введите `exit`.

## Структура проекта

- `src/main/kotlin/kz/shprot/`
  - `Main.kt` - точка входа с консольным интерфейсом
  - `YandexLLMClient.kt` - HTTP клиент для работы с Yandex API
  - `models/YandexApiModels.kt` - data классы для запросов и ответов
