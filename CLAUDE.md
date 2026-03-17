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

## Код

- Любое написание или изменение кода должно соответствовать docs/dev-principles.md и docs/codestyle.md
- Любое изменение в архитектуре или в ключевых механизмах должно соответствовать docs/architecture.md

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

## Документация

[docs/architecture.md](docs/architecture.md) · [docs/dev-principles.md](docs/dev-principles.md) · [docs/codestyle.md](docs/codestyle.md)