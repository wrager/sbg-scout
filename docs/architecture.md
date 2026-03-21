# Архитектура SBG Userscripts APK

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

### Настройки (SettingsActivity)

- `SettingsFragment` (PreferenceFragmentCompat) — автообновление, версия, баг-репорт
- Настройки хранятся в `SharedPreferences` (по умолчанию `_preferences`)

### Локализация

- Английский — язык по умолчанию (`values/strings.xml`)
- Русский — `values-ru/strings.xml`
- Язык определяется по системным настройкам устройства

## WebView

- `SbgWebViewClient` — перехват загрузки страниц, инжекция скриптов, обработка `window.close()`
- JS-бриджи: `ClipboardBridge` (полифил `navigator.clipboard`), `ShareBridge` (открытие URL)
- Инжекция только на `sbg-game.ru/app*`
- Geolocation permissions — запрос и выдача runtime-разрешений

### Настройки в GameActivity

- `SettingsDrawerLayout` — DrawerLayout с ограничением зоны свайпа областью pull-tab
- `SettingsPullTab` — кастомный View (сегмент эллипса с шевроном) на левом краю, 25% от верха
- `SettingsFragment` встроен в drawer панель, выезжает слева при свайпе от таба
- Свайп из других точек левого края не открывает drawer (не мешает WebView)

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
4. **Anmiles script** (sbg.plus) — `anmiles/userscripts`

### Правила конфликтов

- SVP конфликтует с: EUI, CUI, Anmiles script (модификация UI)
- EUI, CUI, Anmiles script — совместимы между собой
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
- `HttpFetcher` — интерфейс HTTP GET (с поддержкой headers), реализация через `HttpURLConnection`

### Инжекция

1. Глобальные переменные (`__sbg_local`, `__sbg_package`, `__sbg_package_version`)
2. Clipboard-полифил
3. Скрипты (каждый в IIFE, обёрнут в try-catch)
4. Группировка по `@run-at`: document-start выполняется в `onPageStarted`, document-end/idle — по событию DOMContentLoaded
5. Ошибки инжекции собираются через `window.__sbg_injection_errors`

## Флоу запуска

1. `LauncherActivity` — список скриптов с тогглами и предупреждениями о конфликтах
2. Первый запуск: автоматическая загрузка предустановленных скриптов (SVP включён по умолчанию)
3. Пользователь настраивает скрипты → нажимает «Запустить» → `GameActivity`
4. `SbgWebViewClient` загружает `sbg-game.ru/app`, инжектирует включённые скрипты
5. JS-бриджи доступны скриптам через `Android.*` и `__sbg_share.*`

## Структура проекта

```
app/src/main/java/com/github/wrager/sbguserscripts/
├── GameActivity.kt          WebView, immersive mode, geolocation, drawer настроек
├── bridge/
│   ├── ClipboardBridge.kt   Полифил navigator.clipboard
│   └── ShareBridge.kt       Открытие URL
├── launcher/
│   ├── LauncherActivity.kt    Стартовый экран, LAUNCHER
│   ├── LauncherViewModel.kt   Состояние, бизнес-логика
│   ├── LauncherUiState.kt     Модели UI-состояния и событий
│   └── ScriptListAdapter.kt   RecyclerView-адаптер
├── game/
│   ├── SettingsDrawerLayout.kt  DrawerLayout с ограничением зоны свайпа
│   └── SettingsPullTab.kt       Визуальный pull-tab (сегмент эллипса)
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
│       ├── HttpFetcher.kt            Интерфейс HTTP
│       ├── DefaultHttpFetcher.kt     Реализация
│       ├── ScriptDownloader.kt       Загрузка скриптов
│       ├── ScriptUpdateChecker.kt    Проверка обновлений
│       ├── ScriptDownloadResult.kt   Success | Failure
│       ├── ScriptUpdateResult.kt     UpdateAvailable | UpToDate | CheckFailed
│       ├── GithubRelease.kt          Модели GitHub-релиза и ассета
│       └── GithubReleaseProvider.kt  Загрузка релизов через GitHub API
├── settings/
│   ├── SettingsActivity.kt  Экран настроек
│   └── SettingsFragment.kt  PreferenceFragmentCompat
└── webview/
    └── SbgWebViewClient.kt  Загрузка страниц, инжекция, close()
```
