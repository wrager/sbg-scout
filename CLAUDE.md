# Правила работы над проектом

Android APK для SBG (мобильная браузерная геолокационная игра). WebView с менеджером юзерскриптов, кешированием ассетов, проверкой обновлений.

## Критические запреты (нарушение = перманентный бан в игре)

1. **Запрещена подмена GPS**
2. **Запрещена автоматизация** игровых действий
3. **Запрещён мультиаккаунт**
4. **Запрещена модификация запросов** к серверу

## CI (перед каждым коммитом)

`./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug`

Если сборка падает — пофиксить и повторить.

## Исследование

- Перед новой фичей/правкой — **сначала искать в `refs/`** (Read/Grep). Если `refs/` нет — запустить `node scripts/fetchRefs.mjs`
- При проблемах с WebView, API игры или платформой — **сначала проверить refs/anmiles/** (референсный APK), потом документация
- Исходники SVP можно также искать в соседней директории (`../sbg-vanilla-plus`) — это рабочая директория разработки SVP. Может содержать незакоммиченные изменения: проверять актуальность по `git log` и `git status`. Папку `dist/` там не смотреть — собирается редко, скорее всего неактуальна. В целом лучше использовать `refs/svp/`, но иногда соседняя директория может быть полезна

## Код

- **Не смешивать Material3 и MaterialComponents.** Приложение использует `Theme.MaterialComponents` — все стили виджетов должны быть из `Widget.MaterialComponents.*`. Стили `Widget.Material3.*` дают некорректные цвета в MaterialComponents-теме. Перед применением стиля — проверить совместимость с темой приложения.
- Любое написание или изменение кода должно соответствовать docs/dev-principles.md и docs/codestyle.md
- Любое изменение в архитектуре или в ключевых механизмах должно соответствовать docs/architecture.md
- **Не угадывать** URL, DOM-классы/ID, структуру страниц и любые факты об игре — искать в refs/ (в т.ч. refs/anmiles/) или спросить пользователя

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

## Документация

[docs/architecture.md](docs/architecture.md) · [docs/dev-principles.md](docs/dev-principles.md) · [docs/codestyle.md](docs/codestyle.md)