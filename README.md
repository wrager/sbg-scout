# ![icon](.github/images/icon.png) SBG Scout

[![Latest release][release-badge]][releases]
[![Build status][ci-badge]][ci]
[![Downloads][downloads-badge]][releases]
[![License MIT][license-badge]][license]

Android-клиент для игры [SBG](https://sbg-game.ru/) со встроенным менеджером юзерскриптов.

## Возможности

- **Менеджер юзерскриптов** — установка по URL или из файла, переустановка, тогглы, предустановленные Vanilla+ и EUI (CUI в один клик без ввода URL), выбор любой версии с GitHub, обнаружение конфликтов, безопасная инжекция с поддержкой `@run-at`, JS-бриджи (clipboard, share, настройки игры)
- **Автообновление** — проверка приложения и скриптов при запуске, диалоги с release notes и прогрессом загрузки, кнопка «Обновить все» для скриптов
- **WebView с игрой** — полноэкранный immersive-режим, геолокация, синхронизация темы и языка из настроек игры, перезагрузка из настроек

## Скачать

[Скачать последний APK][releases-latest]

## Скриншоты

<table><tr>
<td valign="top"><img src=".github/images/screenshots/settings.png" alt="Настройки"></td>
<td valign="top"><img src=".github/images/screenshots/script-manager.png" alt="Менеджер скриптов"></td>
</tr></table>

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

[releases]: https://github.com/wrager/sbg-scout/releases
[releases-latest]: https://github.com/wrager/sbg-scout/releases/latest
[ci]: https://github.com/wrager/sbg-scout/actions/workflows/ci.yml
[license]: LICENSE
[release-badge]: https://img.shields.io/github/v/release/wrager/sbg-scout?style=flat-square
[ci-badge]: https://img.shields.io/github/actions/workflow/status/wrager/sbg-scout/ci.yml?branch=main&style=flat-square
[downloads-badge]: https://img.shields.io/github/downloads/wrager/sbg-scout/total?style=flat-square&cacheSeconds=3600
[license-badge]: https://img.shields.io/github/license/wrager/sbg-scout?style=flat-square
