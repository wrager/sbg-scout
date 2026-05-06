Перегенерировать visual regression baseline'ы через e2e на подключённом эмуляторе.

## Процесс

1. Проверить, что эмулятор подключён: `adb devices`. В списке должен быть `emulator-NNNN device`. Параметры эмулятора (см. memory): API 33, Pixel 4, WebView 101, без Google Play.

   Локаль эмулятора не имеет значения - тесты сами форсят русскую локаль приложения через `LocaleManager.setApplicationLocales` в `@Before`. Тема и SystemUI status bar тоже принудительно фиксируются через `ThemeOverride` и `StatusBarDemoMode` в `@Before`.

2. Запустить:

   ```
   ./gradlew :app:updateVisualRegressionBaselines
   ```

   Task прогоняет тесты с аннотацией `@VisualRegressionTest` (фильтр навешан в `defaultConfig.testInstrumentationRunnerArguments` через `gradle.startParameter`, активируется только при запуске этого task'а или `visualRegressionTest` - на обычный `connectedInstrAndroidTest` не влияет) и перезаписывает PNG в `.github/images/visual-regression/`. Длительность 5-10 мин.

3. `git diff --stat .github/images/visual-regression/` покажет реально изменившиеся baseline'ы. Visual проверка (analyze_image MCP): рендер полный (нет артефактов загрузки), часы 12:00, тема light/dark соответствует имени файла, диалоги в нужной фазе.

**Команда не коммитит сама.** Обновлённые PNG остаются в working tree. Дальше:
- Внутри коммита с UI-изменением - включить обновлённые baseline'ы в тот же коммит (правило: UI-изменения требуют обновления VR baseline'ов в том же коммите, см. CLAUDE.md).
- Если запуск был для проверки стабильности и diff пустой - откатить через `git checkout`.

## Ограничения

- Anti-aliasing рендера WebView недетерминирован между прогонами на ~уровень 8/channel; threshold compare = 0.1% пикселей, поэтому невидимые шумы не фейлят VR. Если после обновления baseline'а тот же запуск даёт diff > 0.1% - искать root cause flakiness, не "запустить ещё раз".
- WebView-экраны (`game-settings`, `login`) только в light: фикстуры без dark CSS, dark-вариант потребовал бы отдельной dark-фикстуры с инлайн dark-CSS.
- `app-settings-light/dark.png` показывают версию из `BuildConfig.VERSION_NAME` без суффикса `-instr` (тест обрезает суффикс). При release-bump версия меняется, baseline протухает - обновляется тем же релизным коммитом.

## Когда вызывается

- Намеренное UI-изменение (новый Preference, изменение цвета/спейсинга, новый виджет) - перед коммитом UI-правки.
- Внутри `/release` ПОСЛЕ обновления версии в `build.gradle.kts` (версия попадает на app-settings baseline).
- При локальной диагностике flakiness, чтобы получить свежий triplet diff в `app/build/reports/visual-regression/`.
