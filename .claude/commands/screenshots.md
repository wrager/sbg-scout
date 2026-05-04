Перегенерировать скриншоты в README через e2e на подключённом эмуляторе.

## Процесс

1. Проверить, что эмулятор подключён: `adb devices`. В списке должен быть `emulator-NNNN device`. Параметры эмулятора (см. memory): API 33, Pixel 4, WebView 101, без Google Play.

   Локаль эмулятора не имеет значения - тесты сами форсят русскую локаль приложения через `LocaleManager.setApplicationLocales` в `@Before` и сбрасывают в `@After`.
2. Запустить:

   ```
   ./gradlew :app:updateReadmeScreenshots
   ```

   Task прогоняет три скриншот-теста с аннотацией `@ReadmeScreenshot` (фильтр навешан в `defaultConfig.testInstrumentationRunnerArguments` через `gradle.startParameter`, активируется только при запуске этого task'а - на обычный `connectedInstrAndroidTest` не влияет) и копирует PNG в `.github/images/screenshots/`. Длительность 3-10 мин.

3. `git status .github/images/screenshots/`. Diff визуально проверить (analyze_image MCP): рендер полный (нет артефактов загрузки), локализация русская, ширина 360 px сохранена.

**Команда не коммитит сама.** Скриншоты остаются в working tree. Дальше:
- Внутри `/release` они входят в общий релизный коммит (вместе с `RELEASE_NOTES.md` и `build.gradle.kts`).
- Вне релиза - закоммитить отдельным коммитом "Обновить скриншоты в README" вручную.

## Ограничения

- `game_settings.png` использует mock-фикстуру `app-page-with-settings-content-realistic.html` - приближение игрового UI без полного игрового CSS/JS. Для точной копии реальной игры локально можно подложить snapshot страницы игры в `refs/game/private/` (gitignored), gradle подхватит его как override фикстуры. Эта поддержка добавляется отдельным шагом, когда в проекте появится локальный snapshot.
- `settings.png` и `script-manager.png` - чисто наш UI приложения, от игры не зависят.

## Когда вызывается

- Шаг 5 в `/release` ПОСЛЕ обновления версии в `build.gradle.kts`, чтобы новая версия попала на скриншот экрана настроек.
- Вручную, когда меняется UI приложения, чтобы проверить актуальность скриншотов вне релизного цикла.
