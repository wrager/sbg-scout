# Архитектура SBG Scout

## Обзор

Android-приложение с WebView, загружающее игру SBG (`sbg-game.ru/app`) и инжектирующее пользовательские скрипты. Один APK заменяет несколько сборок Anmiles SBG APK, добавляя менеджер скриптов с поддержкой известных скриптов, обнаружения конфликтов и автообновлений. При первом запуске бандлированный SVP разворачивается из assets, а затем при наличии сети заменяется на свежую версию из GitHub.

## Activities

| Activity | Назначение |
|---|---|
| `GameActivity` | **LAUNCHER.** WebView с игрой, выдвижная панель настроек с кнопкой-иконкой, provisioning при первом запуске, автообновления |
| `LauncherActivity` | Standalone-менеджер скриптов, открывается из настроек когда WebView недоступен |
| `SettingsActivity` | Хост для `SettingsFragment` вне контекста игры |
| `MainActivity` | Legacy-shim для совместимости с v0.1: перенаправляет в `GameActivity` и завершается |

## UI-архитектура

### GameActivity

- WebView заполняет экран в immersive-режиме
- `DrawerLayout` заблокирован (`LOCK_MODE_LOCKED_CLOSED`) — открывается только по кнопке-иконке приложения в правом верхнем углу, чтобы свайп от края не конфликтовал с WebView
- После открытия drawer разблокируется, позволяя закрыть свайпом; на `onDrawerClosed` снова блокируется
- `settingsContainer` в drawer содержит `SettingsFragment` по умолчанию; при переходе в «Управление скриптами» заменяется на `ScriptListFragment` (embedded) через back stack
- При закрытии drawer back stack сбрасывается, применяются отложенные настройки (например, reload игры)
- Provisioning overlay перекрывает WebView на время первого запуска (скачивание SVP, если bundled-версии не хватило)

### SettingsFragment (PreferenceFragmentCompat)

Используется в двух контекстах: встроенный в drawer `GameActivity` и как содержимое `SettingsActivity`. Категории:

- **Экран** — полноэкранный режим, не гасить экран
- **Скрипты** — управление скриптами (открывает `ScriptListFragment` embedded или `LauncherActivity` standalone), перезагрузка игры
- **Обновления** — авто-проверка при запуске, ручная проверка обновлений приложения, ручная проверка обновлений скриптов
- **О приложении** — версия, баг-репорт с автоматической диагностикой

### ScriptListFragment

Список скриптов с тогглами, меню ⋮ (overflow), предупреждениями о конфликтах. Используется в двух контекстах:

- Standalone в `LauncherActivity` — с кнопкой «Перезагрузить игру» (видима при `reloadNeeded`)
- Embedded в drawer `GameActivity` — с тулбаром обратно к `SettingsFragment`; дополнительные режимы `newEmbeddedAutoCheckInstance` / `newEmbeddedAutoUpdateInstance` для автозапуска проверки/обновления сразу при открытии

Элементы:

- `LauncherViewModel` — управление состоянием: загрузка пресетов, тогглы, конфликты, добавление по URL/из файла, удаление, переустановка, выбор версии, обновления
- `ScriptListAdapter` (ListAdapter + DiffUtil) — RecyclerView-адаптер
- `LauncherUiState` / `ScriptUiItem` — модели UI-состояния
- `LauncherEvent` — одноразовые события через `Channel`
- Меню тулбара: «Обновить все», «Настройки»
- Меню карточки скрипта (⋮): «Выбрать версию» (GitHub) или «Переустановить» (остальные), «Удалить» (не-пресеты)
- FAB «Добавить скрипт» → диалог URL, «Добавить из файла» → системный file picker

### LauncherActivity

Standalone-экран (над `ScriptListFragment` — самостоятельная реализация списка, не использующая фрагмент). Открывается из `SettingsFragment` → «Управление скриптами» при отсутствии `GameActivity`-контекста, либо из «Проверить обновления скриптов». Содержит автообновление-чекбокс, кнопку проверки обновлений и кнопку «Перезагрузить игру» (видима при необходимости reload). При `onResume` показывает pending release notes обновлённых скриптов, если они есть.

### Локализация

- Английский — язык по умолчанию (`values/strings.xml`)
- Русский — `values-ru/strings.xml`
- Язык определяется из настроек игры (`localStorage['settings'].lang`), fallback на системные настройки

## WebView

- `SbgWebViewClient` — перехват `window.close` (закрывает Activity), инжекция скриптов в `onPageStarted`, чтение настроек игры в `onPageFinished`
- JS-бриджи:
  - `ClipboardBridge` — полифил `navigator.clipboard` (`Android.*`)
  - `ShareBridge` — открытие URL (`__sbg_share.*`)
  - `GameSettingsBridge` — уведомления об изменении настроек игры (`__sbg_settings.*`)
- Инжекция только на `sbg-game.ru/app*`
- Geolocation permissions — запрос и выдача runtime-разрешений
- Cookies: CookieManager принимает cookies и third-party cookies для auth
- Remote debugging в debug-сборке, JS console → Logcat через `WebChromeClient.onConsoleMessage`

### Синхронизация темы и языка с игрой

- `GameSettingsReader` парсит JSON из `localStorage['settings']` (поля `theme`, `lang`)
- `GameSettingsBridge` перехватывает `localStorage.setItem('settings', ...)` через JS-обёртку и уведомляет Android
- `SbgWebViewClient` инжектирует обёртку в `onPageStarted`, читает начальные настройки в `onPageFinished`
- `GameActivity` применяет тему через `AppCompatDelegate.setDefaultNightMode()` и язык через `AppCompatDelegate.setApplicationLocales()`
- Последние применённые значения сохраняются в SharedPreferences для предотвращения recreation loop

### Обработка кнопки «Назад»

Приоритет (через `OnBackPressedCallback`):
1. `ScriptListFragment` открыт в drawer → `popBackStack` (вернуться к `SettingsFragment`)
2. Drawer открыт на `SettingsFragment` → закрыть drawer
3. Drawer закрыт → диспатч `backbutton` event в DOM игры, затем `webView.goBack()` или выход

## Менеджер скриптов

### Модель данных

- `UserScript` — скрипт: идентификатор, заголовок (Tampermonkey-формат), URL, контент, enabled, isPreset
- `ScriptHeader` — парсинг `// ==UserScript==` блока (@name, @version, @match, @run-at и др.)
- `ScriptIdentifier` — inline value class, уникальный идентификатор скрипта
- `ScriptVersion` — семантическое сравнение версий
- `ScriptConflict` — описание несовместимости между скриптами

### Предустановленные скрипты

Класс `PresetScripts` хранит список «известных» скриптов с фиксированными download/update URL. В текущей версии:

1. **SVP** (SBG Vanilla+) — `wrager/sbg-vanilla-plus`, `enabledByDefault = true`
2. **EUI** (Enhanced UI) — `egorantonov/sbg-enhanced`
3. **CUI** (Custom UI) — `nicko-v/sbg-cui`

Автоматически при первом запуске устанавливается и включается только SVP (единственный с `enabledByDefault = true`). EUI и CUI видны в списке менеджера и могут быть установлены пользователем одним кликом без ввода URL.

### Правила конфликтов

- SVP конфликтует с: EUI, CUI (модификация UI)
- EUI, CUI — совместимы между собой
- `ConflictDetector` проверяет кандидата против включённых скриптов
- `StaticConflictRules` — жёстко заданные правила

### Хранение скриптов

- **Метаданные** → SharedPreferences (`scripts.xml`), сериализация через `ScriptSerializer`
- **Контент** → `filesDir/scripts/*.user.js` через `ScriptFileStorage`
- `ScriptStorage` — интерфейс: getAll, save, delete, getEnabled, setEnabled
- `InjectionStateStorage` — снапшот идентификаторов и версий скриптов, инжектированных при последней загрузке страницы (для определения, нужен ли reload после изменения набора)

### Установка и provisioning

- `ScriptInstaller.parse()` — парсит raw-контент UserScript и строит `UserScript` **без сохранения**; вызывающий код дополняет результат (sourceUrl, updateUrl, isPreset) через `.copy()` и затем вызывает `save()`
- `ScriptInstallResult` — sealed class: `Parsed(script)` / `InvalidHeader`
- `BundledScriptInstaller` — разворачивает бандлированный SVP из `assets/scripts/sbg-vanilla-plus.user.js` при первом запуске; пресет пропускается, если уже provisioned или уже в хранилище по `sourceUrl`
- `DefaultScriptProvisioner` — автозагружает enabledByDefault-пресеты из сети; хранит список обработанных идентификаторов в SharedPreferences (`provisioned_defaults`), чтобы не повторять попытки при удалении пользователем; при ошибке загрузки оставляет пресет в pending до следующего запуска

### Загрузка и обновление

- `ScriptDownloader` — загрузка скрипта по URL, парсинг заголовка через `ScriptInstaller`, сохранение
- `ScriptUpdateChecker` — сравнение локальной и удалённой версий через `.meta.js`
- `ScriptReleaseNotesProvider` — загрузка и агрегация release notes из GitHub Releases API (от текущей до новой версии)
- `PendingScriptUpdateStorage` — хранение описания обновлений в SharedPreferences для отложенного показа на лаунчере (когда обновление применяется из drawer, release notes показываются при следующем `onResume` `LauncherActivity`)
- `GithubReleaseProvider` — загрузка списка релизов через GitHub Releases API для выбора версии
- `HttpFetcher` — интерфейс HTTP GET (с поддержкой headers, прогресса и бинарной загрузки в файл), реализация через `HttpURLConnection`

### Инжекция

1. Перехват `localStorage.setItem` (обёртка для `GameSettingsBridge`)
2. Глобальные переменные (`__sbg_local`, `__sbg_package`, `__sbg_package_version`)
3. Clipboard-полифил
4. Скрипты (каждый в IIFE, обёрнут в try-catch)
5. Группировка по `@run-at`: document-start выполняется в `onPageStarted`, document-end/idle — по событию DOMContentLoaded
6. Ошибки инжекции собираются через `window.__sbg_injection_errors` и показываются через Toast

## Обновление приложения

- `AppUpdateChecker` — проверка новых версий через GitHub Releases API (`wrager/sbg-scout`), сравнение с `BuildConfig.VERSION_NAME`
- `AppUpdateResult` — sealed class: `UpdateAvailable`, `UpToDate`, `CheckFailed`
- `AppUpdateInstaller` — скачивание APK в `cacheDir/updates/` с коллбэком прогресса, установка через `FileProvider` + `ACTION_VIEW`
- Авто-проверка при запуске: если включена настройка `auto_check_updates` и прошло > 24 ч с последней проверки
- Ручная проверка через кнопку в настройках показывает единый диалог: индикатор проверки → release notes с кнопкой «Скачать» и прогрессом загрузки → «Отмена» отменяет проверку или загрузку

## Диагностика баг-репортов

- `ConsoleLogBuffer` — потокобезопасный кольцевой буфер последних 50 записей `console.error`/`console.warn` из WebView. Заполняется в `GameActivity` через `WebChromeClient.onConsoleMessage`
- `BugReportCollector` — собирает диагностику (устройство, Android, WebView, версия APK, все установленные скрипты с версиями и маркерами статуса, лог ошибок) и формирует:
  - Текст для буфера обмена (полная диагностика)
  - URL для GitHub issue с предзаполненными полями шаблона `bug_report.yml`
- Кнопка «Сообщить об ошибке» в настройках: копирует диагностику → показывает Toast → открывает GitHub Issues
- Работает из обоих контекстов:
  - `GameActivity` — доступен лог консоли и снапшот инжекции (скрипты помечаются как активные/отключённые)
  - `SettingsActivity` без WebView — лог консоли и снапшот недоступны, все enabled-скрипты помечаются как ⏳

## Флоу запуска

1. `GameActivity.onCreate` применяет сохранённые тему и язык **до** `super.onCreate` (чтобы Activity создалась сразу в нужной конфигурации)
2. Настраивается WebView, drawer с `SettingsFragment`, обработка кнопки «Назад»
3. `BundledScriptInstaller` разворачивает бандлированный SVP из assets, если он ещё не установлен
4. `DefaultScriptProvisioner.hasPendingScripts()` — если есть pending-пресеты (например, сеть недоступна при первой установке или bundled-провал), показывается provisioning overlay с прогресс-баром и запускается онлайн-загрузка
5. При ошибке provisioning: «Повторить» / «Продолжить без скриптов»
6. После provisioning: `webView.loadUrl(sbg-game.ru/app)`
7. `SbgWebViewClient` инжектирует localStorage-обёртку + включённые скрипты
8. JS-бриджи доступны скриптам через `Android.*`, `__sbg_share.*`, `__sbg_settings.*`
9. Пользователь тапает кнопку-иконку приложения → drawer с `SettingsFragment`; отсюда можно открыть управление скриптами, запустить проверку обновлений или баг-репорт

## Структура проекта

```
app/src/main/java/com/github/wrager/sbgscout/
├── GameActivity.kt          LAUNCHER. WebView, immersive, geolocation, drawer, provisioning, автообновления
├── MainActivity.kt          Legacy-shim v0.1 → перенаправляет в GameActivity
├── bridge/
│   ├── ClipboardBridge.kt     Полифил navigator.clipboard
│   ├── GameSettingsBridge.kt  Уведомления об изменении настроек игры (localStorage)
│   └── ShareBridge.kt         Открытие URL
├── diagnostic/
│   ├── ConsoleLogBuffer.kt    Кольцевой буфер console.error/warn (последние 50)
│   └── BugReportCollector.kt  Сбор диагностики, формирование clipboard-текста и issue URL
├── game/
│   └── GameSettingsReader.kt    Парсинг JSON настроек игры (theme, lang)
├── launcher/
│   ├── LauncherActivity.kt      Standalone-менеджер скриптов
│   ├── ScriptListFragment.kt    Список скриптов для drawer (embedded) и для LauncherActivity
│   ├── LauncherViewModel.kt     Состояние, бизнес-логика
│   ├── LauncherUiState.kt       Модели UI-состояния и событий
│   └── ScriptListAdapter.kt     RecyclerView-адаптер
├── script/
│   ├── injector/
│   │   ├── ScriptInjector.kt         Генерация JS для инжекции
│   │   ├── InjectionResult.kt        Success | ScriptError
│   │   └── InjectionStateStorage.kt  Снапшот последней инжекции (для определения reload)
│   ├── installer/
│   │   ├── ScriptInstaller.kt          Парсинг header + save()
│   │   ├── ScriptInstallResult.kt      Parsed | InvalidHeader
│   │   └── BundledScriptInstaller.kt   Разворачивание SVP из assets при первом запуске
│   ├── model/
│   │   ├── UserScript.kt          Модель скрипта
│   │   ├── ScriptHeader.kt        Заголовок скрипта
│   │   ├── ScriptIdentifier.kt    Уникальный ID
│   │   ├── ScriptVersion.kt       Сравнение версий
│   │   └── ScriptConflict.kt      Описание конфликта
│   ├── parser/
│   │   └── HeaderParser.kt        Парсер ==UserScript== блока
│   ├── preset/
│   │   ├── PresetScripts.kt         Список известных скриптов (SVP, EUI, CUI)
│   │   ├── PresetScript.kt          Модель пресета
│   │   ├── ConflictDetector.kt      Обнаружение конфликтов
│   │   ├── ConflictRuleProvider.kt  Интерфейс правил
│   │   └── StaticConflictRules.kt   Жёсткие правила
│   ├── provisioner/
│   │   └── DefaultScriptProvisioner.kt  Автозагрузка enabledByDefault-пресетов из сети
│   ├── storage/
│   │   ├── ScriptStorage.kt          Интерфейс хранилища
│   │   ├── ScriptStorageImpl.kt      SharedPreferences + файлы
│   │   ├── ScriptFileStorage.kt      Интерфейс файлового хранилища
│   │   ├── ScriptFileStorageImpl.kt  Реализация
│   │   └── ScriptSerializer.kt       JSON-сериализация
│   └── updater/
│       ├── HttpFetcher.kt                   Интерфейс HTTP (fetch + fetchToFile + progress)
│       ├── DefaultHttpFetcher.kt            Реализация
│       ├── ScriptDownloader.kt              Загрузка скриптов
│       ├── ScriptUpdateChecker.kt           Проверка обновлений
│       ├── ScriptDownloadResult.kt          Success | Failure
│       ├── ScriptUpdateResult.kt            UpdateAvailable | UpToDate | CheckFailed
│       ├── GithubRelease.kt                 Модели GitHub-релиза и ассета
│       ├── GithubReleaseProvider.kt         Загрузка релизов через GitHub API
│       ├── ScriptReleaseNotesProvider.kt    Агрегация release notes скриптов
│       └── PendingScriptUpdateStorage.kt    Хранение pending-обновлений для лаунчера
├── settings/
│   ├── SettingsActivity.kt       Хост для SettingsFragment
│   └── SettingsFragment.kt       PreferenceFragmentCompat, проверка обновлений, баг-репорт
├── updater/
│   ├── AppUpdateChecker.kt     Проверка обновлений через GitHub Releases
│   ├── AppUpdateInstaller.kt   Скачивание и установка APK с прогрессом
│   └── AppUpdateResult.kt      UpdateAvailable | UpToDate | CheckFailed
└── webview/
    └── SbgWebViewClient.kt  Загрузка страниц, инжекция, чтение настроек, close()
```
