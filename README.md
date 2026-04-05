# ![icon](.github/images/icon.png) SBG Scout

[![Latest release][release-badge]][releases]
[![Build status][ci-badge]][ci]
[![Downloads][downloads-badge]][releases]
[![License MIT][license-badge]][license]

Android-клиент для игры [SBG](https://sbg-game.ru/) со встроенным менеджером юзерскриптов.

## Возможности

- **Менеджер юзерскриптов** — установка по URL, удаление, переустановка, включение/выключение
- **Встроенный SVP** — SBG Vanilla+ автоматически устанавливается и включается при первом запуске
- **Известные скрипты без ввода URL** — EUI и CUI доступны в менеджере для установки в один клик
- **Обнаружение конфликтов** — предупреждение при включении несовместимых скриптов
- **Выбор версии с GitHub** — установка любого релиза скрипта по выбору
- **Автообновление приложения** — проверка при запуске, диалог с release notes и прогрессом загрузки
- **Автообновление скриптов** — проверка при запуске, диалог с агрегированными release notes, кнопка «Обновить все»
- **WebView игры** — полноэкранный immersive-режим, геолокация, синхронизация темы и языка из настроек игры
- **Drawer настроек в игре** — выдвижная панель с pull-tab для быстрого доступа к настройкам
- **Инжекция скриптов** — безопасная обёртка в IIFE, поддержка `@run-at` (document-start / document-end / document-idle), обработка ошибок
- **JS-бриджи** — полифил `navigator.clipboard`, открытие URL через share, синхронизация настроек игры с Android
- **Диагностика баг-репортов** — автоматический сбор информации об устройстве, WebView, скриптах и последних ошибках console
- **Локализация** — русский и английский

## Скачать

[Скачать последний APK][releases-latest]

## Требования

- Android 7.0+ (API 24)
- Для сборки: JDK 17, Android SDK 35

## Сборка

```bash
./gradlew assembleDebug
```

## Проверка

```bash
./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug
```

## Документация

- [Архитектура](docs/architecture.md)
- [Принципы разработки](docs/dev-principles.md)
- [Стиль кода](docs/codestyle.md)

## Лицензия

[MIT](LICENSE)

[releases]: https://github.com/wrager/sbg-scout/releases
[releases-latest]: https://github.com/wrager/sbg-scout/releases/latest
[ci]: https://github.com/wrager/sbg-scout/actions/workflows/ci.yml
[license]: LICENSE
[release-badge]: https://img.shields.io/github/v/release/wrager/sbg-scout?style=flat-square
[ci-badge]: https://img.shields.io/github/actions/workflow/status/wrager/sbg-scout/ci.yml?branch=main&style=flat-square
[downloads-badge]: https://img.shields.io/github/downloads/wrager/sbg-scout/total?style=flat-square
[license-badge]: https://img.shields.io/github/license/wrager/sbg-scout?style=flat-square
