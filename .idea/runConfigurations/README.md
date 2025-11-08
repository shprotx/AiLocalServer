# Run Configurations

Этот проект содержит две run configuration:

## 1. Run Server (рекомендуется)
Прямой запуск через Kotlin main class.

**Настройка переменных окружения:**
1. Откройте Run Configuration в IntelliJ IDEA
2. Выберите "Run Server"
3. В поле "Environment variables" добавьте:
   - `YANDEX_API_KEY` - ваш API ключ
   - `YANDEX_FOLDER_ID` - ваш Folder ID
   - `MODEL_TYPE` - `yandexgpt` или `yandexgpt-lite` (опционально)

## 2. Run (Gradle)
Запуск через Gradle task `run`.

**Настройка переменных окружения:**
Добавьте переменные в систему перед запуском:
```bash
export YANDEX_API_KEY=your_key
export YANDEX_FOLDER_ID=your_folder_id
export MODEL_TYPE=yandexgpt
```

## Примечание
Run configuration хранятся в git для удобства.
НЕ КОММИТЬТЕ свои реальные API ключи в XML файлы!