# Архитектура SBG Scout

## Обзор

Android-приложение с WebView, загружающее игру SBG (`sbg-game.ru/app`) и инжектирующее пользовательские скрипты. Один APK заменяет несколько сборок Anmiles SBG APK, добавляя менеджер скриптов с поддержкой известных скриптов, обнаружения конфликтов и автообновлений. При первом запуске бандлированный SVP разворачивается из assets, а затем при наличии сети заменяется на свежую версию из GitHub.

## Activities

| Activity | Назначение |
|---|---|
| `GameActivity` | **LAUNCHER.** WebView с игрой, экран настроек поверх WebView, provisioning при первом запуске, автообновления |
| `LauncherActivity` | Standalone-менеджер скриптов, открывается из настроек когда WebView недоступен |
| `SettingsActivity` | Хост для `SettingsFragment` вне контекста игры |
| `MainActivity` | Legacy-shim для совместимости с v0.1: перенаправляет в `GameActivity` и завершается |

## UI-архитектура

### GameActivity

- WebView заполняет экран в immersive-режиме
- Белая подложка 50 % opacity над WebView, пока интерфейс игры не инициализировался (сигнал — i18next применил переводы, `#settings` получил локализованный текст)
- Сверху по центру — текст «Инициализация игры…» и компактные кнопки «⚙ Scout» (настройки SBG Scout), «⟳ Reload» (перезагрузка WebView); цвета кнопок зависят от темы (в светлой — светло-серый фон с тонким чёрным бордером, в тёмной — тёмно-серый без бордера)
- Двухрежимная кнопка настроек:
  - **Режим загрузки** — нативная «⚙ Scout» видна
  - **Режим игры** — нативная скрывается; в игровую панель настроек (`.settings-content`) инжектируется HTML-кнопка «Настройки приложения», открывающая тот же экран
- Кнопка «⟳ Reload» видна в режиме загрузки (вместе с «⚙ Scout»), скрывается когда игра готова (i18next инициализирован); вызывает `webView.loadUrl(GAME_URL)`
- Сигнал готовности игры — `window.i18next.isInitialized`; polling каждые 100мс в bootstrap-скрипте, инжектируемом в `onPageStarted` (старт отложен до DOMContentLoaded). Сигнал универсален и не зависит от DOM-разметки — работает и под скриптами, переделывающими UI (CUI, EUI)
- Два независимых сигнала от bootstrap'а:
  - `onGameReady` (i18next готов) → снять подложку, скрыть label/«Reload»
  - `onHtmlButtonInjected` (HTML-кнопка успешно вставлена в `.settings-content`) → скрыть нативную «Scout». Под CUI `.settings-content` может отсутствовать — тогда инжекция пропускается и нативная «Scout» остаётся единственным способом открыть настройки
- При reload игры (`onPageStarted`) все loading-элементы показываются снова и включается белая подложка; флаги `gameReady` и `scoutButtonReplaced` сбрасываются
- `settingsContainer` — `FrameLayout` на весь экран поверх WebView, изначально `GONE`; содержит `SettingsFragment` по умолчанию, при переходе в «Управление скриптами» заменяется на `ScriptListFragment` (embedded) через back stack
- Закрытие — через плавающую квадратную кнопку `[x]` снизу по центру; над кнопкой — 1dp разделитель, визуально отделяющий резерв от скроллируемого контента. Если открыт `ScriptListFragment` — клик по `[x]` делает `popBackStack` (возврат к `SettingsFragment`), иначе закрывает экран настроек. Системная «Назад» всегда идёт в WebView (навигация в истории или выход), настройки она не закрывает
- При закрытии back stack сбрасывается, применяются отложенные настройки (например, reload игры)
- Provisioning overlay перекрывает WebView на время первого запуска (скачивание SVP, если bundled-версии не хватило)

### SettingsFragment (PreferenceFragmentCompat)

Используется в двух контекстах: встроенный в экран настроек `GameActivity` и как содержимое `SettingsActivity`. Категории:

- **Экран** — полноэкранный режим, не гасить экран
- **Скрипты** — управление скриптами (открывает `ScriptListFragment` embedded или `LauncherActivity` standalone), перезагрузка игры
- **Обновления** — авто-проверка при запуске, ручная проверка обновлений приложения, ручная проверка обновлений скриптов
- **О приложении** — версия, баг-репорт с автоматической диагностикой

### ScriptListFragment

Список скриптов с тогглами, меню ⋮ (overflow), предупреждениями о конфликтах. Используется в двух контекстах:

- Standalone в `LauncherActivity` — с кнопкой «Перезагрузить игру» (видима при `reloadNeeded`)
- Embedded в экран настроек `GameActivity` — без стрелки «Назад» в тулбаре; возврат к `SettingsFragment` через плавающую `[x]` в `GameActivity`; дополнительные режимы `newEmbeddedAutoCheckInstance` / `newEmbeddedAutoUpdateInstance` для автозапуска проверки/обновления сразу при открытии

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
  - `ScoutBridge` — сигнал готовности игры и открытие экрана настроек из HTML-кнопки (`__sbg_scout.*`)
  - `DownloadBridge` — приём base64-содержимого blob-файлов для сохранения в Downloads (`__sbg_download.*`)
- Инжекция только на `sbg-game.ru/app*`
- Geolocation permissions — запрос и выдача runtime-разрешений
- Скачивание blob-файлов: `DownloadBridge.BOOTSTRAP_SCRIPT` инжектируется в `onPageStarted` до юзерскриптов, оборачивает `URL.createObjectURL` (кеширует blob'ы) и `HTMLAnchorElement.prototype.click` (для blob href + download атрибута читает blob через `FileReader.readAsDataURL` и передаёт в мост). Нельзя использовать `DownloadListener` + `fetch(blobUrl)`, т.к. юзерскрипты сразу после `click()` вызывают `URL.revokeObjectURL` — к моменту срабатывания асинхронного колбэка blob уже revoke'нут. `DownloadBridge` декодирует base64 и сохраняет в `Downloads/` через `MediaStore` (API 29+) или в app-external files dir (API < 29) — без runtime-разрешений
- File picker (`<input type="file">`): `WebChromeClient.onShowFileChooser` запускает SAF через `fileChooserParams.createIntent()`; MIME-фильтр из `accept=` подхватывается автоматически
- Cookies: CookieManager принимает cookies и third-party cookies для auth
- Remote debugging в debug-сборке, JS console → Logcat через `WebChromeClient.onConsoleMessage`

### Синхронизация темы и языка с игрой

- `GameSettingsReader` парсит JSON из `localStorage['settings']` (поля `theme`, `lang`)
- `GameSettingsBridge` перехватывает `localStorage.setItem('settings', ...)` через JS-обёртку и уведомляет Android
- `SbgWebViewClient` инжектирует обёртку в `onPageStarted`, читает начальные настройки в `onPageFinished`
- `GameActivity` применяет тему через `AppCompatDelegate.setDefaultNightMode()` и язык через `AppCompatDelegate.setApplicationLocales()`
- Последние применённые значения сохраняются в SharedPreferences для предотвращения recreation loop
- `SbgScoutApplication.onCreate` применяет сохранённую тему/язык до создания любой Activity — иначе при холодном старте Activity успевает инфлейтить layout с дефолтной (не night) конфигурацией, пока `GameActivity.onCreate` не добежит до `setDefaultNightMode`
- `GameActivity.onConfigurationChanged` (configChanges=uiMode) перечитывает цвета кнопок «Scout»/«Reload»/«[x]»/label'а/сепаратора из текущей темы, т.к. при не-пересоздании Activity view-хи держат значения цветов, разрезолвенные при инфляции

### Обработка кнопки «Назад»

`OnBackPressedCallback` всегда диспатчит `backbutton` event в DOM игры, затем `webView.goBack()` или выход — независимо от того, открыт ли экран настроек (закрытие настроек — только через плавающую `[x]`).

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
- `PendingScriptUpdateStorage` — хранение описания обновлений в SharedPreferences для отложенного показа на лаунчере (когда обновление применяется из экрана настроек, release notes показываются при следующем `onResume` `LauncherActivity`)
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
2. Настраивается WebView, экран настроек с `SettingsFragment`, обработка кнопки «Назад»
3. `BundledScriptInstaller` разворачивает бандлированный SVP из assets, если он ещё не установлен
4. `DefaultScriptProvisioner.hasPendingScripts()` — если есть pending-пресеты (например, сеть недоступна при первой установке или bundled-провал), показывается provisioning overlay с прогресс-баром и запускается онлайн-загрузка
5. При ошибке provisioning: «Повторить» / «Продолжить без скриптов»
6. После provisioning: `webView.loadUrl(sbg-game.ru/app)`
7. `SbgWebViewClient` инжектирует localStorage-обёртку + включённые скрипты
8. JS-бриджи доступны скриптам через `Android.*`, `__sbg_share.*`, `__sbg_settings.*`
9. Пока игра грузится, пользователь видит большую нативную кнопку «Настройки SBG Scout»; после готовности (i18next применён) она скрывается, а внутрь игровой панели настроек инжектируется HTML-кнопка «Настройки приложения» — обе открывают экран `SettingsFragment`, откуда доступны управление скриптами, проверка обновлений и баг-репорт

## Структура проекта

```
app/src/main/java/com/github/wrager/sbgscout/
├── SbgScoutApplication.kt   Применяет сохранённые тему/язык до создания Activity (холодный старт)
├── GameActivity.kt          LAUNCHER. WebView, immersive, geolocation, экран настроек, provisioning, автообновления
├── MainActivity.kt          Legacy-shim v0.1 → перенаправляет в GameActivity
├── bridge/
│   ├── ClipboardBridge.kt     Полифил navigator.clipboard
│   ├── DownloadBridge.kt      Сохранение blob-файлов из WebView в Downloads
│   ├── GameSettingsBridge.kt  Уведомления об изменении настроек игры (localStorage)
│   ├── ScoutBridge.kt         Bootstrap готовности игры + HTML-кнопка открытия настроек
│   └── ShareBridge.kt         Открытие URL
├── diagnostic/
│   ├── ConsoleLogBuffer.kt    Кольцевой буфер console.error/warn (последние 50)
│   └── BugReportCollector.kt  Сбор диагностики, формирование clipboard-текста и issue URL
├── game/
│   └── GameSettingsReader.kt    Парсинг JSON настроек игры (theme, lang)
├── launcher/
│   ├── LauncherActivity.kt      Standalone-менеджер скриптов
│   ├── ScriptListFragment.kt    Список скриптов для GameActivity (embedded) и для LauncherActivity
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

## e2e-инфраструктура

Тестирование пользовательских сценариев на реальном WebView идёт через отдельный buildType `e2e`, локальный fake-сервер и source set `androidTest/`.

### buildType `e2e`

- Наследуется от `debug` (`initWith(debug)`) c `applicationIdSuffix = ".e2e"` — тестовый APK ставится параллельно debug-сборке на эмуляторе, не затирая её.
- Свой `BuildConfig.GAME_APP_URL / GAME_LOGIN_URL / GAME_HOST_MATCH` указывает на `http://127.0.0.1` — вместо `sbg-game.ru`.
- `testBuildType = "e2e"` делает `connectedAndroidTest` запускающим именно e2e-вариант APK. Побочный эффект: AGP генерирует unit-тесты только как `testE2eUnitTest` (см. команду CI в корневом CLAUDE.md).
- `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"` + `clearPackageData` — каждый e2e-тест стартует в своём процессе с чистым состоянием (SharedPreferences, WebView cookies, files).
- Source set `app/src/e2e/` содержит свой `network_security_config.xml` (cleartext разрешён только для `127.0.0.1` и `localhost`) и `AndroidManifest.xml` с `tools:replace` — прод-сборки остаются без послаблений network security.

### Централизация URL игры

Все URL игры читаются из единого объекта [`GameUrls`](../app/src/main/java/com/github/wrager/sbgscout/config/GameUrls.kt):

- В прод-коде — возвращает значения из `BuildConfig.GAME_*`.
- В androidTest — `appUrlOverride/loginUrlOverride/hostMatchOverride` (annotated `@VisibleForTesting internal`) переопределяются через `GameUrlsOverrideRule` на базовый URL `FakeGameServer` (`http://127.0.0.1:<port>`).
- `GameUrls.isGameAppPage(url)` заменил захардкоженные `url?.contains("sbg-game.ru/app")` в `SbgWebViewClient` — guard работает одинаково в проде и на локальном loopback.

### Fake-сервер (`FakeGameServer` + `FakeGameDispatcher`)

Обёртка над OkHttp `MockWebServer`, поднимается на `127.0.0.1:<random-port>` в том же процессе, что и приложение (instrumented-тесты — один процесс, loopback работает без `10.0.2.2`).

`FakeGameDispatcher` маршрутизирует запросы по путям:
- `GET /app[*]` — при отсутствии cookie `hittoken=<fake>` отвечает `302 Location: /login`; иначе — HTML игровой страницы из `server.gamePageBody`.
- `GET /login[*]` — HTML логина из `server.loginPageBody`.
- `POST /login/callback` — `302 Location: /app` + `Set-Cookie` fake-сессии.
- Остальные пути — `404`, `favicon.ico` — `204`.

Тесты задают `gamePageBody` / `loginPageBody` перед стартом сценария. Фикстуры HTML лежат в `app/src/androidTest/assets/fixtures/` и читаются через `AssetLoader` (из `instrumentation.context`, не `targetContext`).

### Синхронизация WebView

`SbgWebViewClient.onGamePageFinished: (() -> Unit)?` — callback, вызываемый после `super.onPageFinished` для страницы, прошедшей `GameUrls.isGameAppPage`. Используется `WebViewIdlingResource` (`IdlingResource`) для ожидания Espresso до момента, когда JS-мосты зарегистрированы и `evaluateJavascript` безопасно вызывать.

`GameActivity.webView` и `GameActivity.sbgWebViewClient` помечены `@VisibleForTesting internal` — `E2ETestBase` подписывает idling на `onGamePageFinished` через `scenario.onActivity { ... }`.

### Структура androidTest

```
app/src/androidTest/java/com/github/wrager/sbgscout/e2e/
├── E2ETestBase.kt                    Жизненный цикл сервера/idling, grant location permission, launchGameActivity
├── infra/
│   ├── FakeGameServer.kt             Обёртка над MockWebServer, gamePageBody/loginPageBody
│   ├── FakeGameDispatcher.kt         Маршрутизация /app, /login, /login/callback
│   ├── WebViewIdlingResource.kt      IdlingResource, становится idle по markLoaded
│   ├── GameUrlsOverrideRule.kt       TestRule для runtime-override GameUrls
│   ├── CookieFixtures.kt             Инъекция fake session-cookie через CookieManager
│   └── AssetLoader.kt                Чтение фикстур из androidTest/assets
├── screens/
│   ├── Screen.kt, GameScreen.kt, SettingsScreen.kt, LoginScreen.kt
└── flows/
    ├── smoke/GameLoadsSmokeTest.kt, ClipboardBridgeE2ETest.kt
    ├── settings/SettingsScreenE2ETest.kt
    └── auth/LoginFlowE2ETest.kt
app/src/androidTest/assets/fixtures/  Минимальные HTML/JSON фикстуры
app/src/e2e/
├── AndroidManifest.xml               tools:replace android:networkSecurityConfig
└── res/xml/network_security_config.xml  cleartext для 127.0.0.1 / localhost
```

### CI

`.github/workflows/e2e.yml` — отдельный workflow на `reactivecircus/android-emulator-runner` (API 33, x86_64, pixel_4), запускается по `pull_request` с path-filter (`app/src/main/**`, `app/src/androidTest/**`, `app/src/e2e/**`, `app/build.gradle.kts`, `gradle/libs.versions.toml`) и `workflow_dispatch`. AVD snapshot кэшируется между запусками. Не добавлен в `push: main`, чтобы не блокировать мердж долгим прогоном эмулятора. Основной `ci.yml` собирает e2e-APK (`assembleE2e`) для быстрого ловли поломок сборки до запуска emulator runner.

### Запрет обращений к прод-серверу

e2e-тесты **никогда** не обращаются к реальному `sbg-game.ru`. Причина — правила игры квалифицируют автоматизированные обращения как нарушение, что приводит к блокировке аккаунта разработчика. Все HTTP-запросы WebView идут на `127.0.0.1:<port>` fake-сервера; централизация URL через `GameUrls` + `BuildConfig` делает это структурным инвариантом, а не договорённостью.

