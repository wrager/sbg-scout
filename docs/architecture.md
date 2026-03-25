# Архитектура SBG Scout

## Обзор

Android-приложение с WebView, загружающее игру SBG (`sbg-game.ru/app/`) и инжектирующее пользовательские скрипты. Один APK заменяет несколько сборок Anmiles SBG APK, добавляя менеджер скриптов с поддержкой предустановленных скриптов, конфликтов и обновлений.

## Activities

| Activity | Назначение |
|---|---|
| `LauncherActivity` | LAUNCHER. Список скриптов с тогглами, конфликты, кнопка «Запустить» |
| `GameActivity` | WebView с игрой, инжекция скриптов, immersive mode, выдвижная панель настроек |
| `SettingsActivity` | Экран настроек из LauncherActivity (PreferenceFragmentCompat) |

## UI-архитектура

### Стартовый экран (LauncherActivity)

- `LauncherViewModel` — управление состоянием: загрузка пресетов, тогглы, конфликты, добавление/удаление/обновление скриптов
- `ScriptListAdapter` (ListAdapter + DiffUtil) — список скриптов с тогглами, меню ⋮ (overflow), предупреждениями о конфликтах
- `LauncherUiState` / `ScriptUiItem` — модель UI-состояния
- `LauncherEvent` — одноразовые события (Toast-сообщения) через `Channel`
- FAB «Добавить скрипт» → диалог ввода URL
- Меню тулбара: «Обновить все», «Настройки»
- Меню карточки скрипта (⋮): «Выбрать версию» (GitHub) / «Переустановить» (остальные), «Удалить» (не-пресеты)
- Первый запуск: автоматическая загрузка всех пресетных скриптов

### Настройки (SettingsActivity / SettingsFragment)

- `SettingsFragment` (PreferenceFragmentCompat) — используется в двух контекстах:
  - В `SettingsActivity` — из LauncherActivity
  - В drawer `GameActivity` — встроен в выдвижную панель
- Категории настроек:
  - **Экран** — полноэкранный режим, не гасить экран
  - **Скрипты** — менеджер скриптов, перезагрузка игры
  - **Обновления** — авто-проверка, проверка обновлений приложения, проверка обновлений скриптов
  - **О приложении** — версия, баг-репорт с автоматической диагностикой

### Локализация

- Английский — язык по умолчанию (`values/strings.xml`)
- Русский — `values-ru/strings.xml`
- Язык определяется из настроек игры (`localStorage['settings'].lang`), fallback на системные настройки

## WebView

- `SbgWebViewClient` — перехват загрузки страниц, инжекция скриптов, чтение настроек игры, обработка `window.close()`
- JS-бриджи:
  - `ClipboardBridge` — полифил `navigator.clipboard` (`Android.*`)
  - `ShareBridge` — открытие URL (`__sbg_share.*`)
  - `GameSettingsBridge` — уведомления об изменении настроек игры (`__sbg_settings.*`)
- Инжекция только на `sbg-game.ru/app*`
- Geolocation permissions — запрос и выдача runtime-разрешений

### Синхронизация темы и языка с игрой

- `GameSettingsReader` парсит JSON из `localStorage['settings']` (поля `theme`, `lang`)
- `GameSettingsBridge` перехватывает `localStorage.setItem('settings', ...)` через JS-обёртку и уведомляет Android
- `SbgWebViewClient` инжектирует обёртку в `onPageStarted`, читает начальные настройки в `onPageFinished`
- `GameActivity` применяет тему через `AppCompatDelegate.setDefaultNightMode()` и язык через `AppCompatDelegate.setApplicationLocales()`
- Последние применённые значения сохраняются в SharedPreferences для предотвращения recreation loop

### Настройки в GameActivity

- `SettingsDrawerLayout` — DrawerLayout с ограничением зоны свайпа областью pull-tab
- `SettingsPullTab` — кастомный View (сегмент эллипса с шевроном) на левом краю, 25% от верха
- `SettingsFragment` встроен в drawer панель, выезжает слева при свайпе от таба
- Свайп из других точек левого края не открывает drawer (не мешает WebView)
- Кнопка «Назад»: приоритетно закрывает ScriptListFragment → drawer → WebView back → exit

## Менеджер скриптов

### Модель данных

- `UserScript` — скрипт: идентификатор, заголовок (Tampermonkey-формат), URL, контент, enabled, isPreset
- `ScriptHeader` — парсинг `// ==UserScript==` блока (@name, @version, @match, @run-at и др.)
- `ScriptIdentifier` — inline value class, уникальный идентификатор скрипта
- `ScriptVersion` — семантическое сравнение версий
- `ScriptConflict` — описание несовместимости между скриптами

### Предустановленные скрипты

1. **SVP** (SBG Vanilla+) — `wrager/sbg-vanilla-plus`
2. **EUI** (Enhanced UI) — `egorantonov/sbg-enhanced`
3. **CUI** (Custom UI) — `nicko-v/sbg-cui`

### Правила конфликтов

- SVP конфликтует с: EUI, CUI (модификация UI)
- EUI, CUI — совместимы между собой
- `ConflictDetector` проверяет кандидата против включённых скриптов
- `StaticConflictRules` — жёстко заданные правила

### Хранение скриптов

- **Метаданные** → SharedPreferences (`scripts.xml`), сериализация через `ScriptSerializer`
- **Контент** → `filesDir/scripts/*.user.js` через `ScriptFileStorage`
- `ScriptStorage` — интерфейс: getAll, save, delete, getEnabled, setEnabled

### Загрузка и обновление

- `ScriptDownloader` — загрузка скрипта по URL, парсинг заголовка, сохранение
- `ScriptUpdateChecker` — сравнение локальной и удалённой версий через `.meta.js`
- `GithubReleaseProvider` — загрузка списка релизов через GitHub Releases API для выбора версии
- `HttpFetcher` — интерфейс HTTP GET (с поддержкой headers и бинарной загрузки в файл), реализация через `HttpURLConnection`

### Инжекция

1. Перехват `localStorage.setItem` (обёртка для `GameSettingsBridge`)
2. Глобальные переменные (`__sbg_local`, `__sbg_package`, `__sbg_package_version`)
3. Clipboard-полифил
4. Скрипты (каждый в IIFE, обёрнут в try-catch)
5. Группировка по `@run-at`: document-start выполняется в `onPageStarted`, document-end/idle — по событию DOMContentLoaded
6. Ошибки инжекции собираются через `window.__sbg_injection_errors`

## Обновление приложения

- `AppUpdateChecker` — проверка новых версий через GitHub Releases API (`wrager/sbg-scout`), сравнение с `BuildConfig.VERSION_NAME`
- `AppUpdateResult` — sealed class: `UpdateAvailable`, `UpToDate`, `CheckFailed`
- `AppUpdateInstaller` — скачивание APK в `cacheDir/updates/` через `HttpFetcher.fetchToFile`, установка через `FileProvider` + `ACTION_VIEW`
- Авто-проверка при запуске: если включена настройка и прошло > 24ч с последней проверки
- Ручная проверка через кнопку в настройках (категория «Обновления»)

## Диагностика баг-репортов

- `ConsoleLogBuffer` — потокобезопасный кольцевой буфер последних 50 записей `console.error`/`console.warn` из WebView. Заполняется в `GameActivity` через `WebChromeClient.onConsoleMessage`
- `BugReportCollector` — собирает диагностику (устройство, Android, WebView, версия APK, включённые скрипты с версиями, лог ошибок) и формирует:
  - Текст для буфера обмена (полная диагностика)
  - URL для GitHub issue с предзаполненными полями шаблона `bug_report.yml`
- Кнопка «Сообщить об ошибке» в настройках: копирует диагностику → показывает Toast → открывает GitHub Issues
- Работает из обоих контекстов: `GameActivity` (с логом консоли) и `SettingsActivity` (без лога)

## Флоу запуска

1. `LauncherActivity` — список скриптов с тогглами и предупреждениями о конфликтах
2. Первый запуск: автоматическая загрузка предустановленных скриптов (SVP включён по умолчанию)
3. Пользователь настраивает скрипты → нажимает «Запустить» → `GameActivity`
4. `SbgWebViewClient` загружает `sbg-game.ru/app`, инжектирует обёртку localStorage + включённые скрипты
5. JS-бриджи доступны скриптам через `Android.*`, `__sbg_share.*`, `__sbg_settings.*`

## Структура проекта

```
app/src/main/java/com/github/wrager/sbgscout/
├── GameActivity.kt          WebView, immersive mode, geolocation, drawer настроек, тема/язык из игры
├── bridge/
│   ├── ClipboardBridge.kt   Полифил navigator.clipboard
│   ├── GameSettingsBridge.kt  Уведомления об изменении настроек игры (localStorage)
│   └── ShareBridge.kt       Открытие URL
├── diagnostic/
│   ├── ConsoleLogBuffer.kt  Кольцевой буфер console.error/warn (последние 50)
│   └── BugReportCollector.kt  Сбор диагностики, формирование clipboard-текста и issue URL
├── game/
│   ├── GameSettingsReader.kt   Парсинг JSON настроек игры (theme, lang)
│   ├── SettingsDrawerLayout.kt  DrawerLayout с ограничением зоны свайпа
│   └── SettingsPullTab.kt       Визуальный pull-tab (сегмент эллипса)
├── launcher/
│   ├── LauncherActivity.kt    Стартовый экран, LAUNCHER
│   ├── LauncherViewModel.kt   Состояние, бизнес-логика
│   ├── LauncherUiState.kt     Модели UI-состояния и событий
│   └── ScriptListAdapter.kt   RecyclerView-адаптер
├── script/
│   ├── injector/
│   │   ├── ScriptInjector.kt      Генерация JS для инжекции
│   │   └── InjectionResult.kt     Success | ScriptError
│   ├── model/
│   │   ├── UserScript.kt          Модель скрипта
│   │   ├── ScriptHeader.kt        Заголовок скрипта
│   │   ├── ScriptIdentifier.kt    Уникальный ID
│   │   ├── ScriptVersion.kt       Сравнение версий
│   │   └── ScriptConflict.kt      Описание конфликта
│   ├── parser/
│   │   └── HeaderParser.kt        Парсер ==UserScript== блока
│   ├── preset/
│   │   ├── PresetScripts.kt       Список предустановленных скриптов
│   │   ├── PresetScript.kt        Модель предустановки
│   │   ├── ConflictDetector.kt    Обнаружение конфликтов
│   │   ├── ConflictRuleProvider.kt  Интерфейс правил
│   │   └── StaticConflictRules.kt   Жёсткие правила
│   ├── storage/
│   │   ├── ScriptStorage.kt       Интерфейс хранилища
│   │   ├── ScriptStorageImpl.kt   SharedPreferences + файлы
│   │   ├── ScriptFileStorage.kt   Интерфейс файлового хранилища
│   │   ├── ScriptFileStorageImpl.kt  Реализация
│   │   └── ScriptSerializer.kt    JSON-сериализация
│   └── updater/
│       ├── HttpFetcher.kt            Интерфейс HTTP (fetch + fetchToFile)
│       ├── DefaultHttpFetcher.kt     Реализация
│       ├── ScriptDownloader.kt       Загрузка скриптов
│       ├── ScriptUpdateChecker.kt    Проверка обновлений
│       ├── ScriptDownloadResult.kt   Success | Failure
│       ├── ScriptUpdateResult.kt     UpdateAvailable | UpToDate | CheckFailed
│       ├── GithubRelease.kt          Модели GitHub-релиза и ассета
│       └── GithubReleaseProvider.kt  Загрузка релизов через GitHub API
├── settings/
│   ├── SettingsActivity.kt  Экран настроек
│   └── SettingsFragment.kt  PreferenceFragmentCompat, проверка обновлений приложения, баг-репорт
├── updater/
│   ├── AppUpdateChecker.kt     Проверка обновлений через GitHub Releases
│   ├── AppUpdateInstaller.kt   Скачивание и установка APK
│   └── AppUpdateResult.kt      UpdateAvailable | UpToDate | CheckFailed
└── webview/
    └── SbgWebViewClient.kt  Загрузка страниц, инжекция, чтение настроек, close()
```
