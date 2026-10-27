# Архитектура SBG Scout

## Обзор

Android-приложение с WebView, загружающее игру SBG (`sbg-game.ru/app`) и инжектирующее пользовательские скрипты. Один APK заменяет несколько сборок Anmiles SBG APK, добавляя менеджер скриптов с поддержкой известных скриптов, обнаружения конфликтов и автообновлений. При первом запуске бандлированный SVP разворачивается из assets, а затем при наличии сети заменяется на свежую версию из GitHub.

## Activities

| Activity | Назначение |
|---|---|
| `GameActivity` | **LAUNCHER.** WebView с игрой, экран настроек поверх WebView, provisioning при первом запуске, автообновления. Deep-link target для `https://sbg-game.ru/*` (`launchMode=singleTask` в prod, `singleTop` в instr) |
| `MainActivity` | Legacy-shim для совместимости с v0.1: перенаправляет в `GameActivity` и завершается |

## UI-архитектура

### GameActivity

- WebView заполняет экран в immersive-режиме
- В правом нижнем углу WebView - компактная нативная крутилка загрузки страницы (`pageLoadingIndicator`), не перехватывающая касания. Она находится под экраном настроек и provisioning-overlay. Состояние считает `PageLoadingTracker`, события ему передают `SbgWebViewClient` и `WebChromeClient`: `onProgressChanged` начинает индикацию ещё до получения ответа сервера; `onPageStarted` отмечает начало основного документа, `onPageFinished` текущей навигации скрывает после завершения документа и его load-blocking ресурсов (CSS, JS, изображения, iframe). Запоздалое завершение другого URL игнорируется. Если документ меняет свой URL через pushState, replaceState или hash до окончания загрузки, `doUpdateVisitedHistory` переносит ожидание на новый URL; `onPageFinished`, который WebView присылает сразу после такой навигации с изменением только fragment, индикатор не скрывает. Механизм работает на любой странице, включая login и магазин, при редиректах, reload, истории, ошибках и отмене. 100% не скрывают индикатор начавшегося документа: Chromium может выдать их после разбора/первого отображения страницы, пока низкоприоритетные ресурсы ещё грузятся. Снижение прогресса обозначает новую навигацию и сбрасывает прежний URL; завершение старого документа до ответа нового игнорируется. Ошибка/отмена до `onPageStarted` завершается по 100% прогресса. Готовность i18next не скрывает крутилку. Фоновые fetch/XHR, тайлы карты после загрузки и переходы внутри документа не включают её. Подготовка скриптов перед открытием страницы использует отдельный provisioning-progress.
  <!-- 🤖 Generated with Claude Opus 5.5 -->
- Между центром содержимого и нижней частью экрана расположен блок `nativeButtonsContainer`: надпись "Загрузка..." и компактные кнопки "Настройки SBG Scout" и "Reload" (перезагрузка WebView) с иконками и обводкой; цвета надписи и кнопок берутся из `scoutButton*` текущей темы. Скрытые элементы убираются из разметки (`GONE`), и блок меняет высоту. Экран настроек и provisioning-overlay скрывают блок целиком
- Центр блока находится посередине между центром доступной области `rootLayout` и центром прежней нижней позиции с отступом `@dimen/native_buttons_bottom_margin` (48dp) от зоны навигационной панели и выреза экрана. Положение пересчитывается при изменении размеров блока, окна и системных отступов, включая клавиатуру и полноэкранный режим. Крутилка остаётся на `@dimen/page_loading_indicator_margin` (12dp) от правого края и выше зоны навигационной панели (`edgeMargin`)
  <!-- 🤖 Generated with GPT-6 Sol -->
- Видимость надписи и кнопок задаёт `nativeButtonsState` (`NativeButtonsState.kt`) по состоянию страницы `PageLoadState`; `GameActivity.renderNativeButtons` применяет его при старте загрузки страницы (`handleGamePageStarted`, `handleOtherPageStarted`), по сигналам bootstrap'а и при закрытии настроек. Надпись видна, только пока грузится игра: загрузку других страниц показывает крутилка
  <!-- 🤖 Generated with Claude Opus 5.5 -->
- Двухрежимная кнопка настроек:
  - **Режим загрузки** — нативная «⚙ Scout» видна
  - **Режим игры** — нативная скрывается; в игровую панель настроек (`.settings-content`) инжектируется HTML-кнопка «Настройки приложения», открывающая тот же экран
- Кнопка "Reload" видна, пока игра грузится (вместе с "Scout"), на странице входа и на странице вне игры, с которой "Назад" не вернёт в игру (`webView.canGoBack()` = false, например страница открыта по ссылке из другого приложения); скрывается, когда игра загрузилась; вызывает `webView.loadUrl(GAME_URL)`
- Сигнал готовности игры — одновременно `window.i18next.isInitialized`, отсутствие игрового экрана загрузки `.loading-screen` и отсутствие `.fatal-error`; polling каждые 100мс в bootstrap-скрипте, инжектируемом в `onPageStarted` (старт отложен до DOMContentLoaded). Игра удаляет `.loading-screen`, только когда все этапы загрузки (тексты, данные, вход в аккаунт, инвентарь, интерфейс, геолокация) прошли успешно или игрок нажал «Хочу», не дожидаясь геолокации; при ошибке этапа экран остаётся, остаётся и "Reload". i18next готов уже на втором этапе, поэтому одного его недостаточно. `.fatal-error` — сообщение, которым игра заменяет страницу при фатальной ошибке (например, при отказе в геолокации). Сигнал не зависит от игрового интерфейса и работает под скриптами, переделывающими UI (CUI, EUI): экран загрузки они не удаляют
- Сигналы от bootstrap'а:
  - `onGameReady` (игра загрузилась) → скрыть надпись и "Reload"
  - `onHtmlButtonInjected` (HTML-кнопка успешно вставлена в `.settings-content`) → скрыть нативную «Scout». Под CUI `.settings-content` может отсутствовать — тогда инжекция пропускается и нативная «Scout» остаётся единственным способом открыть настройки
- При reload игры (`onPageStarted`) состояние снова `GAME_LOADING`: надпись, "Reload" и "Scout" показываются снова; `scoutButtonReplaced` сбрасывается. `.fatal-error` - тоже `GAME_LOADING`: игра не загрузилась, надпись и кнопки остаются
- На страницах вне игры (`/login/`, правила и т.п.) сигнала готовности не будет, поэтому состояние задаётся при их загрузке (`SbgWebViewClient.onOtherPageStarted`). Нативная "Scout" остаётся только на странице входа (`GameUrls.isGameLoginPage`, состояние `LOGIN`): до входа это единственный путь к настройкам приложения. "Reload" остаётся на входе и там, где "Назад" не вернёт в игру. Надписи на этих страницах нет. То же при восстановлении Activity, если WebView восстановил не игру
  <!-- 🤖 Generated with Claude Opus 5.5 -->
- `settingsContainer` — `FrameLayout` на весь экран поверх WebView, изначально `GONE`; содержит `SettingsFragment` по умолчанию, при переходе в «Управление скриптами» заменяется на `ScriptListFragment` (embedded) через back stack
- Закрытие — через плавающую квадратную кнопку `[x]` снизу по центру; над кнопкой — 1dp разделитель, визуально отделяющий резерв от скроллируемого контента. Если открыт `ScriptListFragment` — клик по `[x]` делает `popBackStack` (возврат к `SettingsFragment`), иначе закрывает экран настроек. Системная "Назад" уходит в игру (раздел "Обработка кнопки Назад"), настройки она не закрывает
- При закрытии back stack сбрасывается, применяются отложенные настройки (например, reload игры)
- Provisioning overlay перекрывает WebView на время первого запуска (скачивание SVP, если bundled-версии не хватило)

### SettingsFragment (PreferenceFragmentCompat)

Встроен в экран настроек `GameActivity` (overlay поверх WebView, контейнер `R.id.settingsContainer`). Standalone-запуска нет — единственный entry-point приложения это `GameActivity`. Категории:

- **Экран** — полноэкранный режим, не гасить экран, только вертикальная ориентация
- **Скрипты** — управление скриптами (открывает `ScriptListFragment` в том же контейнере через back stack), перезагрузка игры
- **Обновления** — авто-проверка при запуске, ручная проверка обновлений приложения, ручная проверка обновлений скриптов
- **О приложении** — версия, баг-репорт с автоматической диагностикой

### ScriptListFragment

Список скриптов с тогглами, меню ⋮ (overflow), предупреждениями о конфликтах. Живёт только внутри `GameActivity` как overlay (тот же контейнер, что и `SettingsFragment`, через back stack). Toolbar без меню — возврат к `SettingsFragment` через плавающую `[x]` в `GameActivity`. Дополнительные режимы `newEmbeddedAutoCheckInstance` / `newEmbeddedAutoUpdateInstance` запускают проверку/обновление сразу при открытии (вход из preference «Проверить обновления скриптов» и из диалога release notes).

Элементы:

- `LauncherViewModel` — управление состоянием: загрузка пресетов, тогглы, конфликты, добавление по URL/из файла, удаление, переустановка, выбор версии, обновления
- `ScriptListAdapter` (ListAdapter + DiffUtil) — RecyclerView-адаптер
- `LauncherUiState` / `ScriptUiItem` — модели UI-состояния
- `LauncherEvent` — одноразовые события через `Channel`
- Меню тулбара: «Обновить все», «Настройки»
- Меню карточки скрипта (⋮): «Выбрать версию» (GitHub) или «Переустановить» (остальные), «Удалить» (не-пресеты)
- FAB «Добавить скрипт» → диалог URL, «Добавить из файла» → системный file picker

### Локализация

- Английский — язык по умолчанию (`values/strings.xml`)
- Русский — `values-ru/strings.xml`
- Язык определяется из настроек игры (`localStorage['settings'].lang`), fallback на системные настройки

## WebView

- `SbgWebViewClient` — перехват `window.close` (закрывает Activity), инжекция скриптов в `onPageStarted`, чтение настроек игры в `onPageFinished`
- Внешние ссылки (`SbgWebViewClient.shouldOverrideUrlLoading`). Игра открывает их через `location.href` в том же окне (`confirmOuter`: главная, правила, магазин, каналы Telegram), `window.open` не использует:
  - `about:`, `data:`, `blob:`, `javascript:`, `file:` остаются в WebView
  - Не-веб схемы (`bankXXX://` для оплаты через СБП, `intent:`, `tel:`, `mailto:`) WebView не открывает (`ERR_UNKNOWN_URL_SCHEME`), поэтому в любом фрейме они передаются системе
  - `http(s)` во вложенном фрейме остаются в WebView. В основном фрейме наружу уходят Робокасса (`robokassa.ru`) и Telegram (`t.me`, `telegram.me`) — вместе с поддоменами; остальное, включая магазин игры (`/store/`) и вход через `oauth.telegram.org`, остаётся в WebView
  - Магазин использует текущую сессию WebView без повторного входа. Робокасса открывается через AndroidX Browser `CustomTabsIntent` с заголовком страницы, без `FLAG_ACTIVITY_NEW_TASK` для Activity-контекста: закрытие вкладки возвращает в магазин с сохранённой страницей и историей WebView. Браузер без поддержки Custom Tabs откроет обычную страницу; если браузера нет, показывается общий Toast об отсутствии приложения. Переходы из страницы оплаты в банк обрабатывает браузер. Автоматический возврат из банка в Scout не гарантируется: банк может открыть отдельное окно браузера. Scout не меняет платёжные запросы или адреса возврата
  - `intent:` разбирается через `Intent.parseUri` и ограничивается, как в браузерах: добавляется `CATEGORY_BROWSABLE`, сбрасываются component и selector, а также флаги `FLAG_GRANT_*_URI_PERMISSION` — у приложения есть `FileProvider` с `grantUriPermissions`, и ссылка со страницы не должна выдавать к нему доступ. Некорректная `intent:`-ссылка игнорируется
  - Если запустить приложение не удалось (`ActivityNotFoundException`, `SecurityException`), для `intent:` открывается его `browser_fallback_url` (только `http(s)`), иначе показывается Toast «Не найдено приложение для открытия ссылки». Для контекста, который не Activity, добавляется `FLAG_ACTIVITY_NEW_TASK`
  - Запасной путь для переходов мимо `shouldOverrideUrlLoading` (например, перенаправления на `bankXXX://` в ответ на POST): `onReceivedError` с `ERROR_UNSUPPORTED_SCHEME` в основном фрейме тоже передаёт URL системе и возвращает WebView на предыдущую страницу, если она есть
- JS-бриджи:
  - `ClipboardBridge` — полифил `navigator.clipboard` (`Android.*`)
  - `ShareBridge` — открытие URL (`__sbg_share.*`)
  - `GameSettingsBridge` — уведомления об изменении настроек игры (`__sbg_settings.*`)
  - `ScoutBridge` — сигнал готовности игры и открытие экрана настроек из HTML-кнопки (`__sbg_scout.*`)
  - `DownloadBridge` — приём base64-содержимого blob-файлов для сохранения в Downloads (`__sbg_download.*`)
- Инжекция только на `sbg-game.ru/app*`
- Geolocation permissions — запрос и выдача runtime-разрешений
- Скачивание blob-файлов: `DownloadBridge.BOOTSTRAP_SCRIPT` инжектируется в `onPageStarted` до юзерскриптов, оборачивает `URL.createObjectURL` (кеширует blob'ы) и `HTMLAnchorElement.prototype.click` (для blob href + download атрибута читает blob через `FileReader.readAsDataURL` и передаёт в мост). Нельзя использовать `DownloadListener` + `fetch(blobUrl)`, т.к. юзерскрипты сразу после `click()` вызывают `URL.revokeObjectURL` — к моменту срабатывания асинхронного колбэка blob уже revoke'нут. `DownloadBridge` декодирует base64 и сохраняет в `Downloads/` через `MediaStore` (API 29+) или в app-external files dir (API < 29) — без runtime-разрешений
- User-Agent override для JS: `UserAgentOverride.BOOTSTRAP_SCRIPT` инжектируется первым в `onPageStarted` и через `Object.defineProperty(navigator, 'userAgent', { get })` возвращает UA с вырезанным токеном `; wv`. Нужен для обхода проверки `IsWebView` в EUI (`navigator.userAgent.toLowerCase().includes('wv')`), которая иначе отключает импорт/экспорт скриптов. Настоящий `webView.settings.userAgentString` и HTTP-заголовки не меняются — сервер получает оригинальный UA с суффиксом `SbgScout/…`. Временный workaround; откатить после правки проверки в EUI
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

### Deep-linking (открытие URL игры из внешних приложений)

- Intent-filter в `AndroidManifest.xml` на `GameActivity` — `ACTION_VIEW` + `BROWSABLE` + `<data android:scheme="https" android:host="sbg-game.ru" />`: весь домен игры отдаётся нашему приложению как вариант «чем открыть». Auto-verified App Links не настроены (требуют `.well-known/assetlinks.json` на `sbg-game.ru`, домен не наш) — значит Android показывает системный picker, пользователь один раз выбирает «SBG Scout → всегда».
- `launchMode="singleTask"` в prod: гарантирует, что deep-link из внешнего приложения (Telegram, браузер, мессенджер) открывает `GameActivity` в собственном task'е с нашим `taskAffinity`, а не подшивает её поверх стэка вызывающего приложения. Без `singleTask` Android размещает Activity в task вызывающего (если у intent'а нет `FLAG_ACTIVITY_NEW_TASK`) — пользователь видит «приложение внутри Telegram»: back возвращает в чат, recents не показывает отдельную карточку SBG Scout. `singleTask` также даёт onNewIntent-поведение при повторных deep-link-кликах в живой инстанс. В instr-варианте `launchMode` переопределён на `singleTop` через `tools:replace` в `app/src/instr/AndroidManifest.xml` — `singleTask` несовместим с `ActivityScenario.close()` (Activity застревает в RESUMED на tearDown), а у нас в task всего одна Activity-компонента (`MainActivity` — legacy-shim с `noHistory`, finish'ится сразу), так что `singleTop` функционально эквивалентен `singleTask` для e2e-сценариев.
- `GameActivity.onCreate` и `onNewIntent` извлекают URL из intent через `extractDeepLinkUrl` (строгая проверка `GameUrls.isGameUrl` — `Uri.host == GAME_HOST_MATCH`, защита от произвольного intent, посланного напрямую минуя фильтр манифеста). Если deep-link валиден — грузится в WebView, иначе — дефолтный `GameUrls.appUrl`.
- Deep-link, пришедший во время provisioning'а (pending-пресеты качаются из сети), откладывается в `pendingDeepLinkUrl` и применяется в `finishProvisioning` вместо дефолта.
- Preference «Открывать ссылки sbg-game.ru в приложении» (в категории `game`) запускает системный экран назначения default handler: на API 31+ — `Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` с `package:` URI (сразу попадает на «Supported web addresses» нашего приложения), на API < 31 — `ACTION_APPLICATION_DETAILS_SETTINGS` как fallback. Программно назначить приложение обработчиком нельзя — решение всегда принимает пользователь через системный UI, это ограничение безопасности Android.

### Обработка кнопки "Назад"

`OnBackPressedCallback` в `GameActivity` на открытом экране настроек нажатие игнорирует (закрытие настроек - только через плавающую `[x]`), иначе передаёт его в `GameBackNavigation`:

1. В документе игры выполняется `BACK_PRESS_SCRIPT`. Он запоминает открытые игровые попапы (`.popup:not(.hidden)` без экрана загрузки и без попапов внутри скрытых), диспатчит отменяемое событие `backbutton` и возвращает `handled`, если страница вызвала `preventDefault` (так делает EUI) или до события был открыт попап. Верхний открытый попап закрывается его собственной кнопкой `.popup-close`, чтобы отработал игровой `closePopup`; попапу без кнопки (levelup, score) добавляется класс `hidden`. CUI закрывает попап точки и профиля в своём слушателе `backbutton` сам, событие не отменяя.
2. При `unhandled` вызывается `webView.goBack()`, если предыдущая запись истории есть и это не страница входа (`GameUrls.isGameLoginPage`): возврат на `/login` с живой сессией разлогинивает игрока. Иначе callback отключается и нажатие уходит системе: корневую Activity она с Android 12 уводит в фон, а не завершает.

Решение принимается в callback'е `evaluateJavascript`, то есть после нажатия асинхронно.
<!-- 🤖 Generated with Claude Fable 5.1 -->

## Менеджер скриптов

### Модель данных

- `UserScript` — скрипт: идентификатор, заголовок (Tampermonkey-формат), URL, контент, enabled, isPreset
- `ScriptHeader` — парсинг `// ==UserScript==` блока (@name, @version, @match, @run-at и др.)
- `ScriptIdentifier` — inline value class, уникальный идентификатор скрипта
- `ScriptVersion` — сравнение числовых компонентов версии до первого символа, отличного от цифры или точки; весь последующий суффикс игнорируется, включая числа внутри него. Недостающие компоненты считаются нулями. Для обновления скриптов `isUpdateFor` сравнивает стабильный релиз с установленной версией: больший номер или равный номер при любом суффиксе установленной версии означает доступное обновление. То же правило используется для отбора release notes скриптов. Проверка обновлений APK использует только числовое сравнение, поэтому `-debug` и `-instr` не вызывают обновления на тот же номер
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
- `BundledScriptBeacon` — после установки бандлированного скрипта делает fire-and-forget GET на `releases/download/v{version}/{asset}` (pinned URL), чтобы инкрементировать GitHub release download counter у конкретной доставленной версии. Бандл сам по себе сетевой запрос не генерирует — без beacon'а автор скрипта не увидит факт установки. Идемпотентен по паре `(identifier, version)`: состояние хранится в SharedPreferences (`bundled_script_beacon_pinged` как set строк `"{id}:{version}"`), при обновлении APK с новой версией скрипта отправляется новый ping, при сетевой ошибке ключ не сохраняется (retry на следующем старте). Список бандлированных пресетов — в `PresetScripts.BUNDLED`
- `DefaultScriptProvisioner` — автозагружает enabledByDefault-пресеты из сети; хранит список обработанных идентификаторов в SharedPreferences (`provisioned_defaults`), чтобы не повторять попытки при удалении пользователем; при ошибке загрузки оставляет пресет в pending до следующего запуска

### Загрузка и обновление

- `ScriptDownloader` — загрузка скрипта по URL, парсинг заголовка через `ScriptInstaller`, сохранение
- `ScriptUpdateChecker` — сравнение локальной и удалённой версий. Для `updateUrl` вида `github.com/{owner}/{repo}/releases/(latest/download|download/{tag})/...` идёт в `GithubReleaseProvider` и сравнивает по `tag_name` (с `removePrefix("v")`) — это не инкрементит GitHub release download counter на фоновой проверке. Mono-repo fallback: если `tag_name` (после `removePrefix("v")`) оказывается **меньше** текущей версии скрипта (один тег на несколько asset'ов с разными версиями, например `egorantonov/sbg-enhanced` где тег = EUI version, а CUI version отдельная), автоматически переключается на legacy-путь и скачивает файл для парсинга `@version`. Для прочих URL (raw.githubusercontent, произвольные хостинги) — legacy-путь через `HttpFetcher` + `HeaderParser` по `.meta.js`
- `ScriptReleaseNotesProvider` — загрузка и агрегация release notes из GitHub Releases API (от текущей до новой версии)
- `GithubReleaseProvider` — загрузка списка релизов через GitHub Releases API для выбора версии
- `HttpFetcher` — интерфейс HTTP GET (с поддержкой headers, прогресса и бинарной загрузки в файл), реализация через `HttpURLConnection`

### Инжекция

1. Перехват `localStorage.setItem` (обёртка для `GameSettingsBridge`)
2. Глобальные переменные (`__sbg_local`, `__sbg_package`, `__sbg_package_version`)
3. Clipboard-полифил
4. Вся инжекция (preamble + скрипты) собирается в **один `evaluateJavascript`-вызов** — атомарность гарантирует, что DOMContentLoaded не вклинится между скриптами. Скрипты отсортированы по приоритету: скрипты с `document.open()` первыми (они перестраивают страницу и должны стартовать до остальных). Каждый скрипт в отдельном IIFE с try-catch
5. Группировка по `@run-at`: `document-start` выполняется сразу (immediate batch), остальные — deferred batch. Внутри deferred batch: скрипты с `document.open()` запускаются на `DOMContentLoaded` (аналог Tampermonkey document-idle); остальные — через readyState polling (WebView не файрит DOMContentLoaded после `document.close()`, в отличие от Chrome browser — подтверждено диагностикой)
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
- Работает из `GameActivity`: доступен лог консоли и снапшот инжекции (скрипты помечаются как активные/отключённые)

## Флоу запуска

1. `GameActivity.onCreate` применяет сохранённые тему и язык **до** `super.onCreate` (чтобы Activity создалась сразу в нужной конфигурации)
2. Настраивается WebView, экран настроек с `SettingsFragment`, обработка кнопки «Назад»
3. `BundledScriptInstaller` разворачивает бандлированный SVP из assets, если он ещё не установлен
4. `DefaultScriptProvisioner.hasPendingScripts()` — если есть pending-пресеты (например, сеть недоступна при первой установке или bundled-провал), показывается provisioning overlay с прогресс-баром и запускается онлайн-загрузка
5. При ошибке provisioning: «Повторить» / «Продолжить без скриптов»
6. После provisioning: `webView.loadUrl(sbg-game.ru/app)`
7. `SbgWebViewClient` инжектирует localStorage-обёртку + включённые скрипты
8. JS-бриджи доступны скриптам через `Android.*`, `__sbg_share.*`, `__sbg_settings.*`
9. Пока игра грузится, пользователь видит большую нативную кнопку «Настройки SBG Scout»; после полной загрузки игры она скрывается, а внутрь игровой панели настроек инжектируется HTML-кнопка «Настройки приложения» — обе открывают экран `SettingsFragment`, откуда доступны управление скриптами, проверка обновлений и баг-репорт

## Структура проекта

```
app/src/main/java/com/github/wrager/sbgscout/
├── SbgScoutApplication.kt   Применяет сохранённые тему/язык до создания Activity (холодный старт)
├── GameActivity.kt          LAUNCHER. WebView, immersive, geolocation, экран настроек, provisioning, автообновления
├── MainActivity.kt          Legacy-shim v0.1 → перенаправляет в GameActivity
├── NativeButtonsState.kt    Надпись и кнопки поверх WebView по состоянию страницы
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
│   ├── ScriptListFragment.kt    Список скриптов (overlay внутри GameActivity)
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
│       └── ScriptReleaseNotesProvider.kt    Агрегация release notes скриптов
├── settings/
│   └── SettingsFragment.kt       PreferenceFragmentCompat, проверка обновлений, баг-репорт
├── updater/
│   ├── AppUpdateChecker.kt     Проверка обновлений через GitHub Releases
│   ├── AppUpdateInstaller.kt   Скачивание и установка APK с прогрессом
│   └── AppUpdateResult.kt      UpdateAvailable | UpToDate | CheckFailed
└── webview/
    ├── PageLoadingTracker.kt  Состояние индикатора загрузки основного документа
    └── SbgWebViewClient.kt  Загрузка страниц, инжекция, чтение настроек, close(), внешние ссылки
```

## e2e-инфраструктура

Тестирование пользовательских сценариев на реальном WebView идёт через отдельный buildType `instr` (сокращение от *instrumentation* — по задаче Gradle `connectedInstrAndroidTest`), локальный fake-сервер и source set `androidTest/`.

### buildType `instr`

- Наследуется от `debug` (`initWith(debug)`) c `applicationIdSuffix = ".instr"` — тестовый APK ставится параллельно debug-сборке на эмуляторе, не затирая её.
- Свой `BuildConfig.GAME_APP_URL / GAME_LOGIN_URL / GAME_HOST_MATCH` указывает на `http://127.0.0.1` — вместо `sbg-game.ru`.
- `testBuildType = "instr"` делает `connectedAndroidTest` запускающим именно instr-вариант APK (задача `connectedInstrAndroidTest`). Побочный эффект: AGP генерирует unit-тесты только как `testInstrUnitTest` (см. команду CI в корневом AGENTS.md). Это обычные JVM unit-тесты из `app/src/test/`, не инструментированные — имя складывается по шаблону `test<BuildType>UnitTest`.
- `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"` + `clearPackageData` — каждый e2e-тест стартует в своём процессе с чистым состоянием (SharedPreferences, WebView cookies, files).
- Source set `app/src/instr/` содержит свой `network_security_config.xml` (cleartext разрешён только для `127.0.0.1` и `localhost`) и `AndroidManifest.xml` с `tools:replace` — прод-сборки остаются без послаблений network security.

### Централизация URL игры

Все URL игры читаются из единого объекта [`GameUrls`](../app/src/main/java/com/github/wrager/sbgscout/config/GameUrls.kt):

- В прод-коде — возвращает значения из `BuildConfig.GAME_*`.
- В androidTest — `appUrlOverride/loginUrlOverride/hostMatchOverride` (annotated `@VisibleForTesting internal`) переопределяются через `GameUrlsOverrideRule` на базовый URL `FakeGameServer` (`http://127.0.0.1:<port>`).
- `GameUrls.isGameAppPage(url)` заменил захардкоженные `url?.contains("sbg-game.ru/app")` в `SbgWebViewClient` — guard работает одинаково в проде и на локальном loopback.

### Fake-сервер (`FakeGameServer` + `FakeGameDispatcher`)

Обёртка над OkHttp `MockWebServer`, поднимается на `127.0.0.1:<random-port>` в том же процессе, что и приложение (instrumented-тесты — один процесс, loopback работает без `10.0.2.2`).

OkHttp 5 инициализируется явно в `E2ETestBase.setUpE2E` до запуска fake-сервера.
`androidTest/AndroidManifest.xml` удаляет автоматически добавленный Startup provider
из тестового APK: AndroidX Startup уже входит в APK приложения, а его классы
исключаются из тестового dex. Без этого отдельный запуск служебной Activity из
AndroidX Test падает с `ClassNotFoundException` при создании provider.

`FakeGameDispatcher` маршрутизирует запросы по путям:
- Путь, заданный через `server.stubResponse(path)`, проверяется раньше остальных маршрутов; ключ - путь без query. Заглушка `/app` отвечает без проверки cookie `hittoken`.
  <!-- 🤖 Generated with Claude Opus 5.5 -->
- `GET /app[*]` — при отсутствии cookie `hittoken=<fake>` отвечает `302 Location: /login`; иначе — HTML игровой страницы из `server.gamePageBody`.
- `GET /store/[*]` — при отсутствии cookie `hittoken=<fake>` отвечает `302 Location: /login`; иначе — HTML магазина из `server.storePageBody`.
- `GET /login[*]` — HTML логина из `server.loginPageBody`.
- `POST /login/callback` — `302 Location: /app` + `Set-Cookie` fake-сессии.
- `GET /rules[*]` — HTML из `server.rulesPageBody`: страница игрового хоста вне игры и вне входа (например, `/rules/game/`).
- Остальные пути — `404`, `favicon.ico` — `204`.

Тесты задают `gamePageBody` / `loginPageBody` / `rulesPageBody` / `storePageBody` перед стартом сценария. Фикстуры HTML лежат в `app/src/androidTest/assets/fixtures/` и читаются через `AssetLoader` (из `instrumentation.context`, не `targetContext`).

### Синхронизация WebView

`SbgWebViewClient.onGamePageFinished: (() -> Unit)?` — callback, вызываемый после `super.onPageFinished` для страницы, прошедшей `GameUrls.isGameAppPage`. Используется `WebViewIdlingResource` (`IdlingResource`) для ожидания Espresso до момента, когда JS-мосты зарегистрированы и `evaluateJavascript` безопасно вызывать.

`GameActivity.webView` и `GameActivity.sbgWebViewClient` помечены `@VisibleForTesting internal` — `E2ETestBase` подписывает idling на `onGamePageFinished` через `scenario.onActivity { ... }`.

### Структура androidTest

```
app/src/androidTest/java/com/github/wrager/sbgscout/e2e/
├── E2ETestBase.kt                    Жизненный цикл сервера/idling, grant location permission, launchGameActivity
├── infra/
│   ├── FakeGameServer.kt             Обёртка над MockWebServer, gamePageBody/loginPageBody/rulesPageBody/storePageBody
│   ├── FakeGameDispatcher.kt         Маршрутизация /app, /login, /login/callback, /rules
│   ├── WebViewIdlingResource.kt      IdlingResource, становится idle по markLoaded
│   ├── GameUrlsOverrideRule.kt       TestRule для runtime-override GameUrls
│   ├── CookieFixtures.kt             Инъекция fake session-cookie через CookieManager
│   ├── SelectedTestsFilter.kt        Фильтр раннера для шардов `android.ps1 test -AllTests`
│   └── AssetLoader.kt                Чтение фикстур из androidTest/assets
├── screens/
│   ├── Screen.kt, GameScreen.kt, SettingsScreen.kt, LoginScreen.kt
│   ├── LoadingUiState.kt             Видимость надписи о загрузке и нативных "Reload" и "Scout" для проверок
│   └── ViewPlacement.kt              Положение блока кнопок и индикатора загрузки относительно содержимого и системных панелей
└── flows/
    ├── smoke/GameLoadsSmokeTest.kt, ClipboardBridgeE2ETest.kt, GameBackNavigationE2ETest.kt
    ├── settings/SettingsScreenE2ETest.kt
    ├── loading/GameLoadingE2ETest.kt, OtherPagesE2ETest.kt  Надпись и кнопки при загрузке игры и на страницах вне игры
    └── auth/LoginFlowE2ETest.kt
app/src/androidTest/assets/fixtures/  Минимальные HTML/JSON фикстуры
app/src/instr/
├── AndroidManifest.xml               tools:replace android:networkSecurityConfig
└── res/xml/network_security_config.xml  cleartext для 127.0.0.1 / localhost
```

### CI

Локальный Windows-запуск использует те же Gradle-задачи через `scripts/android.ps1`.
`setupAndroid.ps1` устанавливает инструменты и создаёт выделенный AVD, а `test`
дожидается загрузки и проверяет API 33/WebView 101 перед `connectedInstrAndroidTest`.
AVD запускается с 2 ядрами, 2 ГБ RAM и SwiftShader без Vulkan. SwiftShader не
освобождает память процесса QEMU после тестов, поэтому `test -AllTests` выполняет
набор шардами, каждый на свежем эмуляторе.
<!-- 🤖 Generated with Claude Opus 5.5 -->
Скрипты общие для человека, Codex и Claude Code; пути, разрешения и команды описаны
в [agent-setup.md](agent-setup.md). Это инфраструктура разработки, в APK она не входит.
Для остановки зависшего AVD сохраняются PID и время запуска его host-процесса:
если AVD недоступен, его консоль не отвечает или эмулятор не завершился после
`emu kill`, завершается только подтверждённое дерево этого процесса.
<!-- 🤖 Generated with Claude Opus 5.5 -->

`.github/workflows/e2e.yml` — отдельный workflow на `reactivecircus/android-emulator-runner` (API 33, x86_64, pixel_4), запускается по `pull_request` с path-filter (`app/src/main/**`, `app/src/androidTest/**`, `app/src/instr/**`, `app/build.gradle.kts`, `gradle/libs.versions.toml`) и `workflow_dispatch`. CI использует 2 ядра, 2 ГБ RAM, heap 256 МБ и SwiftShader без Vulkan. Функциональный набор делится на четыре шарда в matrix job `functional`, VR выполняется в job `visual`; у каждого job свой эмулятор, чтобы память QEMU не росла на всём наборе. Данные AVD кешируются, каждый прогон выполняет холодную загрузку без восстановления snapshot. Не добавлен в `push: main`, чтобы не блокировать мердж долгим прогоном эмулятора. Основной `ci.yml` собирает instr-APK (`assembleInstr`) для быстрого ловли поломок сборки до запуска emulator runner.

### Запрет обращений к прод-серверу

e2e-тесты **никогда** не обращаются к реальному `sbg-game.ru`. Причина — правила игры квалифицируют автоматизированные обращения как нарушение, что приводит к блокировке аккаунта разработчика. Все HTTP-запросы WebView идут на `127.0.0.1:<port>` fake-сервера; централизация URL через `GameUrls` + `BuildConfig` делает это структурным инвариантом, а не договорённостью.


## CI и выпуск приложения

`ci.yml` проверяет и собирает debug/instr/release. Для PR из ветки репозитория
job `check` прикрепляет debug APK с номером запуска и попытки; SHA в комментарии
обозначает собранный merge commit. `pr-title.yml` проверяет Conventional Commit
в заголовке PR, который становится сообщением squash-коммита в `main`.

До Gradle обычный CI восстанавливает отдельный постоянный debug-keystore из
`DEBUG_STORE` в `ANDROID_USER_HOME` во временном каталоге runner. Новые PR APK подписаны одним ключом;
локальная подпись не меняется. Fork/Dependabot собираются без этого секрета и
не публикуют preview. Отсутствующий или невалидный ключ блокирует обычный CI.

`release.yml` реагирует на push в `main`: release-please поддерживает release PR
и после его ручного merge создаёт тег и draft. Зависимый `publish` собирает
точный SHA релиза, проверяет подпись APK и публикует draft только после загрузки.
Ручной запуск Release на main восстанавливает только существующий пустой draft:
`retry-target` сверяет стабильный тег с подготовленным squash-коммитом release PR.
Job `recover` исполняется из main, а `publish` собирает точный SHA существующего тега.
Оба пути автоматически завершают публикацию в CI. После всех проверок
`actions/create-github-app-token` создаёт installation token для текущего репозитория
с `Contents: write` и `Workflows: write`; только шаг публикации получает этот токен.
Post-step отзывает его при завершении job. Client ID хранится в variable
`RELEASE_APP_CLIENT_ID`, PEM-ключ - в secret `RELEASE_APP_PRIVATE_KEY`.
Проверка наличия настроек выполняется до сборки; обычный `GITHUB_TOKEN` job `publish`
имеет только права чтения. App token позволяет обновить draft старого SHA с другими workflow,
для которого прав `GITHUB_TOKEN` недостаточно.
Проверка версии APK использует `aapt` по абсолютному пути из Build Tools 36.0.0;
наличие `apkanalyzer` в PATH не требуется.
<!-- 🤖 Generated with GPT-6.1 Sol -->
Перед публикацией `scripts/checkReleaseSigning.mjs` сравнивает наборы SHA-256
сертификатов нового APK и APK предыдущего опубликованного стабильного релиза.
Предыдущий выбирается по максимальной версии ниже текущего тега среди всех
страниц GitHub Releases; draft, prerelease и текущий релиз пропускаются.
Оба APK должны пройти `apksigner verify --print-certs`; смена сертификата,
отсутствие предыдущего релиза или его единственного APK, ошибки API/загрузки
останавливают выпуск. Временный APK удаляется после проверки. Релизный ключ
по-прежнему берётся из `RELEASE_STORE` и не используется для debug.
Версия синхронизируется в `version.txt`, manifest и числах Gradle штатным updater.
Пользовательские notes и README PNG готовятся вручную в release PR через
`scout-release`. До тестов и скриншотов `scripts/updateBundledSvp.mjs`
обновляет бандл SVP из последнего стабильного GitHub Release: API выбирает тег,
загрузка идёт по привязанному к нему URL релизного `.user.js`. Проверяются
имя, namespace, версия, размер и SHA-256 из API (если доступен); после полной
проверки файл заменяется атомарно и коммитится вместе с материалами релиза.
Режим `--check` сравнивает весь бандл с актуальным артефактом без записи.
CI release PR и `publish` до сборки и перед публикацией выполняют эту проверку;
ошибка сети, невалидный артефакт или устаревший бандл блокируют выпуск.
Перед загрузкой APK workflow также сравнивает SVP внутри архива с закоммиченным файлом.
Обычные Gradle-сборки используют закоммиченный файл и не обновляют его из сети.
Подготовка и восстановление описаны в
[инструкции PR и релизов](agent-setup.md#pr-и-релизы).

## Visual regression

Pixel-by-pixel сравнение скриншотов UI с зафиксированными baseline в `.github/images/visual-regression/`. На каждом PR прогон `visualRegressionTest` сравнивает фактический рендер с baseline; различие выше threshold (per-channel diff > 8 + > 0.1% пикселей) фейлит CI и публикует triplet `[baseline | actual | highlight]` в артефактах.

### Покрытие (11 baseline'ов)

- `app-settings-{light,dark}.png` — settings overlay (PreferenceFragment поверх GameActivity).
- `script-manager-{light,dark}.png` — список скриптов (SVP enabled, EUI скачен но disabled, CUI с download-иконкой).
- `dialog-app-update-{light,dark}.png` — диалог проверки обновления приложения в фазе UpdateAvailable.
- `dialog-script-update-{light,dark}.png` — диалог release-notes после auto-check скриптов.
- `game-settings-light.png` — popup игровых настроек в WebView с инжектированной кнопкой "SBG Scout".
- `login-light.png` - fake-страница авторизации с нативными кнопками "Настройки SBG Scout" и "Reload" между центром и нижней частью экрана.
- `game-loading-light.png` - экран загрузки игры (разметка и тексты этапов из `refs/game/index.html` без стилей игры) с надписью "Загрузка...", "Настройки SBG Scout" и "Reload" между центром и нижней частью экрана, без индикатора загрузки страницы.
  <!-- 🤖 Generated with GPT-6 Sol -->

WebView-экраны (`game-settings`, `login`, `game-loading`) только в light: фикстуры без dark CSS, dark-вариант потребовал бы отдельной dark-фикстуры.

### Архитектура: device-side capture, host-side compare

Тесты с аннотацией `@VisualRegressionTest` пишут только `actual.png` через [`VisualRegressionCapture`](../app/src/androidTest/java/com/github/wrager/sbgscout/e2e/visualregression/VisualRegressionCapture.kt) в test storage. Сравнение делает host-side gradle-задача `compareVisualRegressionBaselines` (pure JVM через `javax.imageio`) — baseline не пакуется в test-APK, режим update = просто overwrite baseline.

- `captureFullNative(name)` — весь экран эмулятора (диалоги, login, popup'ы).
- `captureScrollableNative(name, recyclerView)` — scroll-stitched длинный bitmap (settings, script-manager) через переиспользуемый [`ScrollStitchCapture`](../app/src/androidTest/java/com/github/wrager/sbgscout/e2e/screenshots/ScrollStitchCapture.kt).
- Native-разрешение (1080xN), без масштабирования: pixel-diff чувствителен к anti-aliasing и font-hinting, которые `Bitmap.createScaledBitmap` сглаживает.

### Детерминизация

- [`ThemeOverride`](../app/src/androidTest/java/com/github/wrager/sbgscout/e2e/visualregression/ThemeOverride.kt) — записывает `KEY_APPLIED_GAME_THEME` в SharedPreferences до `launchGameActivity()` и вызывает `AppCompatDelegate.setDefaultNightMode`. Без этого тема осталась бы AUTO и тесты были бы недетерминированы по системной теме эмулятора.
- [`StatusBarDemoMode`](../app/src/androidTest/java/com/github/wrager/sbgscout/e2e/visualregression/StatusBarDemoMode.kt) — через SystemUI demo mode broadcast'ы фиксирует часы (12:00), батарею (100%), иконки сети и скрывает notifications. Демо-режим стабилизирует исходные кадры; финальные полноэкранные и прокручиваемые PNG исключают системные панели по WindowInsets.
- Локаль приложения — русская, через `LocaleManager.setApplicationLocales`.
- WebView-сценарии `login` и `game-loading` ждут исчезновения панели буфера обмена SystemUI по resource ID `com.android.systemui:id/clipboard_preview` (`ClipboardOverlay`), независимо от текста и системной локали. Если панель не исчезла за 10 секунд, тест завершается до сохранения PNG.
- Фиксированные stub'ы FakeGameServer для динамических данных (версии релизов, body release notes).

### Gradle-задачи

- `./gradlew visualRegressionTest` — прогон + сравнение с baseline. Зелёный = регрессий нет.
- `./gradlew updateVisualRegressionBaselines` - прогон + перезапись baseline в `.github/images/visual-regression/`. Запускается при намеренных UI-изменениях, включая видимые версии Scout/SVP; `git diff` показывает реально изменившиеся файлы, коммитятся только просмотренные изменения. В Prepare release причина обновления - смена видимой версии, а не сам факт релиза.
- `./gradlew copyVisualRegressionBaselines` / `compareVisualRegressionBaselines` — host-side финализаторы.
- `./gradlew checkReadmeLoadingSpinner` - проверяет сохранённый `game_loading.png` без эмулятора: у видимого индикатора есть дуга и пустая середина.

README-снимки создаются локально через `updateReadmeScreenshots` при подготовке релиза. `game_loading.png` сохраняется в исходной ширине 1080 px и отображается в README с шириной 360 px. `game_settings.png` может использовать локальный приватный snapshot из `refs/game/private`; mock-вариант настроек отдельно покрыт VR baseline `game-settings-light.png`.

Перед захватом README-снимка проверяются физический кадр 1080x2280, плотность 440 dpi и масштаб шрифта 1.0. Несовпадение останавливает генерацию с фактическими параметрами устройства. Снимки шириной 360 px уменьшаются через промежуточные размеры, кратные итоговому, с коэффициентом не более двух за шаг: для 1080 px это 1080 -> 720 -> 360. Это сохраняет тонкие штрихи текста, которые теряются при уменьшении втрое за один проход. Проверка тонких линий по обеим осям и при разных сдвигах входит в `ReadmeScreenshotCaptureTest`.

Скилл `scout-visual-regression` описывает проверку и обновление baseline для Codex и Claude Code (см. [общий источник](../.agents/skills/scout-visual-regression/SKILL.md)).

### CI

`e2e.yml` - reusable workflow, вызываемый из `ci.yml` по общему плану проверок; ручной `workflow_dispatch` запускает все наборы. Функциональные тесты и `visualRegressionTest` выполняются в отдельных jobs со свежими эмуляторами, чтобы ограничить накопление памяти QEMU. Функциональный прогон исключает `@VisualRegressionTest` через `notAnnotation`, поэтому VR-сценарии исполняются один раз - в отдельном прогоне со сравнением PNG. Ключ AVD-кэша включает профиль Pixel 4, чтобы baseline всегда сравнивались при разрешении 1080x2280 и density 440 dpi. Diff-картинки и actual.png попадают в отдельные артефакты `e2e-reports-*` при падении.

При изменении только PNG в `.github/images/visual-regression/` выбирается visual job без функционального e2e. План учитывает общие Gradle-файлы, wrapper и настройки JVM, влияющие на тестируемую сборку.

Генерация локального game snapshot предшествует сборке androidTest assets и моделям lint: совместный запуск полного CI и VR не зависит от порядка выполнения этих задач.

Полноэкранные и прокручиваемые VR-снимки исключают системные status/navigation bar по WindowInsets текущей Activity. Сравнивается интерфейс приложения; значки сети и контраст системных кнопок AVD не создают ложные регрессии. Региональные снимки сохраняют заданные границы.

## Выбор проверок CI и агентов

`scripts/planChecks.mjs` — общий источник выбора проверок по изменённым путям. CI job `changes` вычисляет diff PR через merge-base base/head SHA, а для push — точную пару before/after; локальный режим добавляет staged, unstaged и untracked. Git возвращает NUL-разделённые имена без распознавания rename: проверяются и удалённый, и новый путь. Неизвестный путь включает полный набор без сетевого SVP; ошибка Git/планирования завершает job с ошибкой.

`ci.yml` сохраняет required checks `check` и `agent-skills`, условно запускает jobs и отдельные шаги, а также вызывает e2e только для PR. Если планирование падает или не возвращает флаг группы, оба required jobs запускаются и падают на проверке плана. При успешном плане ненужные jobs получают Skipped, допустимый для branch protection. Debug artifact и комментарий создаются только при выбранной debug-сборке. Release PR всегда получает полный набор и сетевую проверку актуальности SVP.

`pr-title.yml` остаётся независимым обязательным workflow для всех PR; `release.yml` обрабатывает каждый push main через release-please и публикует APK при `release_created` либо после успешного `recover`. Оба пути используют GitHub App для публикации. Подробная матрица и локальные команды - [ci-checks.md](ci-checks.md).

Черновые release notes и подтверждения хранятся в помеченном комментарии release PR;
после редактуры пользователя финальный блок notes помещается в описание PR.
RELEASE_NOTES.md и .release-notes.json схемы 2 формируются общей командой. Проверка
связывает финальные notes, main, версию и хеш содержимого, а publish повторяет её
на SHA выпуска. Подробности: [процесс релиза](releasing.md).

🤖 Generated with GPT-6.1 Sol
