# Правила работы над проектом

SBG Scout — Android-клиент для SBG (мобильная браузерная геолокационная игра). WebView с менеджером юзерскриптов, кешированием ассетов, проверкой обновлений.

Общие инструкции для Codex и Claude Code. Редактировать этот файл; `CLAUDE.md` только импортирует его.

## Окружение и скиллы

- Подготовка окружения, команды для PowerShell и Bash: [docs/agent-setup.md](docs/agent-setup.md).
- Исходники приложения: `app/src/main/`; JVM-тесты: `app/src/test/`; e2e: `app/src/androidTest/`; тестовая конфигурация: `app/src/instr/`.
- Общий источник скиллов — `.agents/skills/scout-*/SKILL.md`. Codex обнаруживает их напрямую; `.claude/skills/` содержит генерируемые копии для Claude Code. Менять только исходники, затем запускать `node scripts/syncAgentSkills.mjs`. Копии коммитятся вместе с исходниками; CI проверяет их актуальность через `node scripts/syncAgentSkills.mjs --check`.
- При проверке приложения использовать `scout-verify`; для скриншотов — `scout-screenshots`; для подготовки release PR от release-please — `scout-release`; для текста анонса — `scout-announce`. В Codex: `$scout-release`, в Claude: `/scout-release`.
- Скилл можно прочитать по пути вручную, если текущая сессия ещё не обновила список. Новые скиллы называть с префиксом `scout-`.

## Критические запреты (нарушение = перманентный бан в игре)

1. **Запрещена подмена GPS**
2. **Запрещена автоматизация** игровых действий
3. **Запрещён мультиаккаунт**
4. **Запрещена модификация запросов** к серверу

## CI (перед каждым коммитом)

`./gradlew ktlintCheck detekt lintInstr testInstrUnitTest assembleDebug assembleInstr assembleRelease`

В PowerShell использовать `./gradlew.bat` с теми же аргументами. Проверка скиллов: `node scripts/syncAgentSkills.mjs --check`; тесты синхронизации: `node --test scripts/syncAgentSkills.test.mjs`.

Если сборка падает — пофиксить и повторить.

**Это нижняя планка, не полная верификация.** Эта команда покрывает lint + JVM unit-тесты + сборку всех вариантов; e2e на эмуляторе сюда НЕ входят (отдельный workflow `.github/workflows/e2e.yml`, локально — `connectedInstrAndroidTest`). Если правка затрагивает `app/src/androidTest/` (новый тест, изменение POM, изменение E2ETestBase) или поведение, покрываемое существующим e2e, — обязательный локальный прогон `./gradlew connectedInstrAndroidTest` ДО push с подключённым эмулятором: API 33, Pixel 4, WebView 101, образ `default` без Google Play. Минимум — прогнать изменённый/новый класс через `./gradlew connectedInstrAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.github.wrager.sbgscout.e2e.flows.smoke.GameLoadsSmokeTest"`, подставив нужное полное имя класса. Флаг `--tests` используется только для JVM-тестов.

Если JDK, SDK, зависимости или эмулятор недоступны, указать точную причину и непройденные проверки; не выдавать их за успешные. Инструкции по подготовке окружения должны храниться в репозитории, без зависимости от личной памяти конкретного агента.

**Почему `testInstrUnitTest`, а не `testDebugUnitTest`.** Из-за `testBuildType = "instr"` в `app/build.gradle.kts` AGP генерирует задачу unit-тестов только для instr-варианта. Это обычные JVM unit-тесты из `app/src/test/`, не требуют эмулятора — имя складывается по шаблону `test<BuildType>UnitTest` и не означает «instrumented». Сами инструментированные тесты живут в `app/src/androidTest/` и запускаются через `connectedInstrAndroidTest`. `assembleInstr` добавлен в CI, чтобы убедиться, что instr-сборка (для e2e-прогона) не сломана. `assembleRelease` — чтобы release-конфигурация (proguard/R8, signing-гейтинг через env) не уехала в красное незамеченной до момента релиза.

## Исследование

- Перед новой фичей/правкой игрового поведения, WebView или юзерскриптов — **сначала искать в `refs/`** (`rg`, чтение файлов). Если `refs/` нет — запустить `node scripts/fetchRefs.mjs`. Для правок только документации/инструментов агентов загрузка игровых референсов не нужна.
- При проблемах с WebView, API игры или платформой — **сначала проверить refs/anmiles/** (референсный APK), потом документация
- Исходники SVP можно также искать в соседней директории (`../sbg-vanilla-plus`) — это рабочая директория разработки SVP. Может содержать незакоммиченные изменения: проверять актуальность по `git log` и `git status`. Папку `dist/` там не смотреть — собирается редко, скорее всего неактуальна. В целом лучше использовать `refs/svp/`, но иногда соседняя директория может быть полезна

## Вшитые скрипты

- **Вшитые скрипты (`app/src/main/assets/scripts/`) обновляются ТОЛЬКО из GitHub releases.** Соседние директории (`../sbg-vanilla-plus` и т.д.) — для исследования исходников, НЕ для вшивания в APK. Источник для вшивания — всегда release-артефакт с GitHub.

## Код

- **Не смешивать Material3 и MaterialComponents.** Приложение использует `Theme.MaterialComponents` — все стили виджетов должны быть из `Widget.MaterialComponents.*`. Стили `Widget.Material3.*` дают некорректные цвета в MaterialComponents-теме. Перед применением стиля — проверить совместимость с темой приложения.
- Любое написание или изменение кода должно соответствовать docs/dev-principles.md и docs/codestyle.md
- Любое изменение в архитектуре или в ключевых механизмах должно соответствовать docs/architecture.md. **Обновлять docs/architecture.md сразу при изменении архитектуры — не спрашивать.**
- **Не угадывать** URL, DOM-классы/ID, структуру страниц и любые факты об игре — искать в refs/ (в т.ч. refs/anmiles/) или спросить пользователя

## Тесты

- **Новое UI-поведение требует e2e-теста.** Unit-тест на изолированную логику (GameUrls, утилиты) не заменяет e2e-тест на UI-flow: диалоги, счётчики тапов, навигация, сохранение состояния в prefs. E2e-тесты живут в `app/src/androidTest/`, запускаются через `connectedInstrAndroidTest`.
- Правило: любое новое поведение покрывается ЛИБО unit-тестом (если логика изолируема), ЛИБО e2e-тестом (если поведение завязано на UI или Activity), ЛИБО обоими.

- **UI-изменения требуют обновления visual regression baseline'ов в том же коммите.** Если правка меняет наблюдаемый рендер любого из 10 baseline-сценариев (settings overlay, script manager, диалоги обновлений, popup игровых настроек, login) - запустить `./gradlew :app:updateVisualRegressionBaselines` (или `/visual-regression-update`) и закоммитить обновлённые PNG из `.github/images/visual-regression/` вместе с кодом. Без обновления baseline'ов CI workflow `e2e.yml` падает на намеренное изменение. Подробности архитектуры VR - в `docs/architecture.md`.

## Скриншоты README

- Скриншоты в `.github/images/screenshots/` (`game_settings.png`, `settings.png`, `script-manager.png`) обновляются как часть каждого релиза тем же коммитом, что bump версии в `app/build.gradle.kts`.
- Источник истины — e2e-генератор, не ручные снимки. Скилл `scout-screenshots` запускает три тест-класса в `app/src/androidTest/.../e2e/screenshots/` через `:app:updateReadmeScreenshots` (gradle-обёртка над `connectedInstrAndroidTest` с фильтром по `@ReadmeScreenshot` + `copyReadmeScreenshots`). Эмулятор: API 33, Pixel 4, WebView 101. Локаль приложения форсится на русскую внутри тестов через `LocaleManager.setApplicationLocales`, локаль эмулятора не имеет значения.
- В `scout-release` скриншоты обновляются ПОСЛЕ `versionMajor`/`versionMinor`/`versionPatch` в `app/build.gradle.kts`, чтобы новая версия попала на экран настроек из `BuildConfig.VERSION_NAME`.
- Итоговый squash-коммит release PR включает `RELEASE_NOTES.md`, `version.txt`, `.release-please-manifest.json`, `app/build.gradle.kts` и `.github/images/screenshots/` одной транзакцией. Скилл скриншотов сам не коммитит — это решает вызывающий сценарий.
- `game_settings.png` использует mock-фикстуру (`app-page-with-settings-content-realistic.html`), потому что полные стили игры не лежат в репо. Локальный snapshot в `refs/game/private/` (gitignored через `/refs/`) подхватывается задачей `inlineGameSnapshot` как override фикстуры.

## Терминология

| Термин | Описание |
|---|---|
| СБГ / SBG | Название игры (Скромная Браузерная Геолокационка / Simplest Browser Geoloc) |
| SVP | SBG Vanilla+ — юзерскрипт `wrager/sbg-vanilla-plus` |
| EUI | SBG Enhanced UI — юзерскрипт `egorantonov/sbg-enhanced` |
| CUI | SBG Custom UI — юзерскрипт `nicko-v/sbg-cui` |
| Anmiles | Юзерскрипт `anmiles/userscripts` (sbg.plus) |
| Точка / Point | Игровая локация на карте |
| Ключ / Ref | Предмет инвентаря, привязанный к точке |
| Ядро / Core | Предмет для простановки на точке |
| Катализатор / Cat | Предмет для атаки точки |
| СЛ / FW | Режим следования за игроком на карте |
| ОРПЦ / OPS | Кнопка инвентаря в верхней панели |

## Референсы

Загрузка: `node scripts/fetchRefs.mjs` → `refs/`

| Референс | Путь в refs/ | Назначение |
|---|---|---|
| SVP sources | `svp/src/` | Исходники SVP |
| SVP release | `releases/sbg-vanilla-plus.user.js` | Собранный SVP .user.js |
| Anmiles APK | `anmiles/` | WebView-настройки, авторизация, JS-мосты, инжекция |
| EUI sources | `eui/src/` | Исходники EUI для понимания API |
| CUI sources | `cui/` | Исходники CUI |
| EUI/CUI releases | `releases/` | Собранные .user.js |
| OpenLayers | `ol/ol.js` | Картографическая библиотека игры |
| Login page HTML | `game/login.html` | Страница авторизации (`sbg-game.ru/login`); редирект с `/app` для неавторизованных |
| Game HTML + script | `game/index.html`, `game/script.js` | Страница игры (`sbg-game.ru/app`); стартовый URL приложения |

Ручной контент (не скачивается автоматически; см. инструкции в stub-файлах):
- `refs/game/dom/login-body.html` — DOM экрана авторизации (из DevTools)
- `refs/game/dom/game-body.html` — DOM игрового экрана после авторизации (из DevTools)
- `refs/game/css/variables.css` — CSS custom properties (из DevTools)
- `refs/game/har/` — HAR-файлы сетевых запросов из DevTools (авторизация, загрузка игры)
- `refs/screenshots/` — скриншоты UI

## PR и релизы

- Заголовки PR — Conventional Commits; merge в main — только squash с заголовком PR. Правила версий и настройки GitHub: [docs/agent-setup.md](docs/agent-setup.md#pr-и-релизы).
- Версию предлагает release-please в отдельном PR. Готовить notes и скриншоты в его ветке; не создавать локальный тег. Выпуск происходит после ручного merge подготовленного release PR.
- В release PR сохранять служебную структуру тела, заголовок и метки бота. После его обновлений повторно проверять версию, notes и PNG перед merge.

## Release notes

- **Группировать по пользовательским фичам, а не по коммитам.** Несколько коммитов, реализующих одну фичу = один пункт. Пользователю важна фича целиком, а не промежуточные шаги разработки.
- **Не включать внутренние фиксы текущего релиза.** Если баг появился и был исправлен в рамках одного релиза — пользователь предыдущей версии его не видел, упоминать нечего. В release notes попадают только фиксы багов предыдущих версий.
- **Верифицировать каждое утверждение по коду.** Коммит-сообщения могут быть неточными или описывать промежуточное состояние. Перед включением в release notes — проверить в коде, что фича реально делает. Нарушение = враньё в changelog.

## Документация

[docs/architecture.md](docs/architecture.md) · [docs/dev-principles.md](docs/dev-principles.md) · [docs/codestyle.md](docs/codestyle.md)
