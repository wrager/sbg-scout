---
name: scout-screenshots
description: Перегенерировать три README-скриншота SBG Scout через e2e на Android-эмуляторе, в том числе при подготовке релиза.
---

# Скриншоты README

Работать из корня репозитория по `AGENTS.md`. Подготовка эмулятора: `docs/agent-setup.md`. В PowerShell заменить `./gradlew` на `./gradlew.bat`.

1. Проверить `adb devices`: нужен подключённый эмулятор в состоянии `device`. Профиль: API 33, Pixel 4, WebView 101, образ `default` без Google Play. Если устройств несколько, выбрать нужный через `ANDROID_SERIAL`.
2. При подготовке релиза сначала должна быть обновлена версия в `app/build.gradle.kts`: экран настроек показывает `BuildConfig.VERSION_NAME`.
3. Запустить:

   ```sh
   ./gradlew :app:updateReadmeScreenshots
   ```

   Задача запускает три класса из `app/src/androidTest/java/com/github/wrager/sbgscout/e2e/screenshots/`, фильтруя тесты по `@ReadmeScreenshot`, затем копирует PNG в `.github/images/screenshots/`. На обычный `connectedInstrAndroidTest` фильтр не влияет. Тесты сами устанавливают русскую локаль приложения и сбрасывают её после прогона.
4. Проверить `git status --short -- .github/images/screenshots/` и открыть изображения доступным инструментом просмотра: `game_settings.png`, `settings.png`, `script-manager.png`. Проверить полный рендер, отсутствие артефактов загрузки, русскую локализацию и ширину 360 px.
5. Оставить PNG в рабочем дереве. Скилл сам не создаёт коммит. В релизе скриншоты входят в общий коммит с версией и `RELEASE_NOTES.md`.

`settings.png` и `script-manager.png` показывают нативный UI. `game_settings.png` использует mock-фикстуру `app-page-with-settings-content-realistic.html`, если нет локального snapshot. Задача `inlineGameSnapshot` подхватывает сохранённую HTML-страницу с каталогом `*_files/` из gitignored `refs/game/private/`. Snapshot и приватные данные в Git не добавлять. Тесты обращаются только к локальному fake-серверу.

Если эмулятор или SDK недоступны, сообщить причину; не заменять генератор ручными снимками и не утверждать, что скриншоты обновлены.
