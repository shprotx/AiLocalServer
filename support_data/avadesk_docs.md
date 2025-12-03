# Avadesk - Техническая документация

**Avadesk** — Android-приложение для управления складскими задачами, построенное на Jetpack Compose с использованием современных архитектурных паттернов Android-разработки.

## Описание

Приложение предназначено для автоматизации складских процессов: управления заявками, сканирования штрихкодов, маркировки товаров, работы с коробками и паллетами, печати этикеток, а также интеграции с картами для отслеживания местоположения.

## Технологии

### Основной стек
| Технология | Версия | Назначение |
|------------|--------|------------|
| **Kotlin** | 2.0.0 | Основной язык разработки |
| **Jetpack Compose** | BOM | Декларативный UI фреймворк |
| **Hilt** | — | Dependency Injection |
| **Coroutines** | — | Асинхронное программирование |
| **Room** | — | Локальная база данных |
| **Navigation Compose** | — | Навигация между экранами |

### Сеть и данные
| Технология | Назначение |
|------------|------------|
| **Retrofit + OkHttp** | REST API клиент |
| **Appwrite** | Backend-as-a-Service |
| **Kotlin Serialization** | Сериализация JSON |
| **Paging 3** | Пагинация данных |

### Медиа и сканирование
| Технология | Назначение |
|------------|------------|
| **CameraX + ML Kit** | Сканирование штрихкодов |
| **ZXing** | Генерация штрихкодов и QR-кодов |
| **FFmpeg Kit** | Сжатие аудио/видео |
| **Coil** | Загрузка изображений |
| **PDFBox** | Генерация PDF документов |

### Сервисы
| Технология | Назначение |
|------------|------------|
| **Yandex MapKit** | Карты и геолокация |
| **Firebase Messaging** | Push-уведомления |
| **Huawei HMS TTS** | Синтез речи |
| **WorkManager** | Фоновые задачи |
| **ESC/POS Printer** | Печать на термопринтерах |

## Архитектура

### Общая структура

Проект использует **feature-based модульную архитектуру** с принципами **Clean Architecture**:

```
dev.platovco.sapistar/
├── core/                      # Общая функциональность
│   ├── base/                  # Базовые классы
│   │   ├── data/             # Слой данных
│   │   │   ├── api/          # API клиенты (Appwrite, Retrofit)
│   │   │   ├── database/     # Room база данных
│   │   │   ├── repository/   # Репозитории
│   │   │   ├── files/        # Работа с файлами
│   │   │   ├── media/        # Аудио/видео плееры
│   │   │   ├── printer/      # Печать на принтерах
│   │   │   ├── notifications/# Push-уведомления
│   │   │   ├── scanner/      # Сканер штрихкодов
│   │   │   ├── storage/      # Локальное хранилище
│   │   │   ├── voice/        # Голосовые сообщения (TTS)
│   │   │   ├── util/         # Утилиты и расширения
│   │   │   └── workmanager/  # Фоновые задачи
│   │   ├── domain/           # Доменные модели
│   │   └── presentation/     # Базовые ViewModel и контракты
│   ├── theme/                # Тема приложения
│   └── uicommon/             # Переиспользуемые UI-компоненты
├── di/                       # Модули Hilt
├── feature/                  # Feature-модули
├── navigation/               # Навигация
└── activity/                 # MainActivity
```

### MVI-паттерн

Приложение использует **MVI (Model-View-Intent)** архитектуру:

- **UIState** - неизменяемое состояние экрана
- **UIEvent** - пользовательские действия
- **UIEffect** - одноразовые эффекты (навигация, snackbar)

## Feature-модули

| Модуль | Описание |
|--------|----------|
| **auth** | Авторизация пользователей через Appwrite |
| **tasks** | Список задач с фильтрацией |
| **taskpage** | Детальная страница задачи |
| **closetask** | Выполнение задач (самый сложный модуль) |
| **archive** | Архив выполненных задач |
| **cache** | Управление кэшем |
| **map** | Карты и геолокация (Yandex MapKit) |
| **settings** | Настройки приложения |
| **printer** | Управление принтерами |
| **splash** | Экран загрузки |
| **permissions** | Запрос разрешений |
| **currenttasks** | Текущие задачи |

### Типы задач (closetask)

| Тип задачи | Описание |
|------------|----------|
| **FormMarker** | Маркировка товаров через формы |
| **DeliveryMarker (Boxes)** | Приёмка с подсчётом коробок |
| **DeliveryMarker (Products)** | Приёмка с подсчётом товаров |
| **DeliveryMarker (Sorting)** | Сортировка товаров |
| **Scanner** | Сканирование штрихкодов |
| **AddStoreBarcodesToForm** | Добавление магазинных штрихкодов |
| **BoxOrderCounter** | Подсчёт коробок в заказе |
| **Checklist** | Чек-листы |

## UI-компоненты

Переиспользуемые компоненты в `core/uicommon/`:

| Пакет | Компоненты |
|-------|------------|
| **buttons** | ButtonDefault, ButtonOutlined |
| **dialogs** | WarningDialog, FullScreenImageDialog |
| **items** | TaskListItem |
| **loading** | LoadingScreenPlaceHolder |
| **menu** | GlobalMenu, DropdownMenu |
| **table** | LazyTableView, TableHeader |
| **textfields** | RTextField, HtmlWebView |
| **toolbar** | AppToolbar |

## Тема приложения

Кастомная система темизации через объект `AppTheme`:
- `AppTheme.colors` - цветовая палитра
- `AppTheme.typography` - типографика
- `AppTheme.paddings` - отступы
- `AppTheme.sizes` - размеры

## Dependency Injection

Hilt-модули:
- **AppwriteModule** - конфигурация Appwrite SDK
- **CoroutineModule** - диспетчеры корутин (@IoDispatcher, @MainDispatcher)
- **DataModule** - Data sources (API, database, file managers)
- **RepositoryModule** - биндинги репозиториев
- **PresentationModule** - ViewModel и Interactor
- **PrinterModule** - компоненты печати
- **WorkerModule** - WorkManager конфигурация

## Навигация

Deep link схема: `avadesk://newtask/{taskUuid}/{isFromDeeplink}`

Навигация использует string-based routes с поддержкой deep linking.

## Версионирование

| Параметр | Значение |
|----------|----------|
| **compileSdk** | 35 |
| **minSdk** | 29 (Android 10) |
| **targetSdk** | 35 |
| **versionCode** | 49 |
| **versionName** | 2.12.0 |

## Разрешения

Приложение запрашивает:
- `INTERNET` — сетевые запросы
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — геолокация
- `ACCESS_BACKGROUND_LOCATION` — фоновая геолокация
- `READ_MEDIA_*` — доступ к медиафайлам
- `RECORD_AUDIO` — запись аудио
- `CAMERA` — камера для сканирования
- `POST_NOTIFICATIONS` — push-уведомления
- `BLUETOOTH` / `BLUETOOTH_CONNECT` — подключение принтера

## Ключевые компоненты

### BarcodeScanner (core/base/data/scanner/)
Сканирование штрихкодов через CameraX + ML Kit. Поддерживает EAN-13, EAN-8, QR, Code 128, UPC.

### PrinterManager (core/base/data/printer/)
Управление ESC/POS принтерами через Bluetooth и USB. Поддержка Zebra, TSC.

### VoiceManager (core/base/data/voice/)
Голосовые подсказки через Huawei HMS TTS.

### PdfManager (core/base/data/files/)
Генерация PDF-документов через PDFBox.

### InternalDownloadManager (core/base/data/files/)
Загрузка и выгрузка файлов, управление медиа.

### SyncWorker (core/base/data/workmanager/)
Фоновая синхронизация через WorkManager.

## Обработка ошибок

- ViewModels обрабатывают ошибки через `exceptionHandler` в `BaseViewModel`
- Сообщения пользователю через `SnackbarManager.showMessage()`
- Голосовая обратная связь через `VoiceManager.speakError()`
- Логирование через Timber

## Поддержка устройств

- Все устройства с Google Play Services
- Huawei устройства с HMS (отдельная поддержка push-уведомлений и TTS)
