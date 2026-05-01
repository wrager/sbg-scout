Перегенерировать скриншоты в README через e2e на подключённом эмуляторе.

## Процесс

1. Проверить, что эмулятор подключён: `adb devices`. В списке должен быть `emulator-NNNN device`. Параметры эмулятора (см. memory): API 33, Pixel 4, WebView 101, без Google Play.

   Локаль эмулятора не имеет значения - тесты сами форсят русскую локаль приложения через `LocaleManager.setApplicationLocales` в `@Before` и сбрасывают в `@After`.
2. Прогнать скриншот-тесты с фильтром по аннотации `@ReadmeScreenshot`:

   ```
   ./gradlew :app:connectedInstrAndroidTest -Pandroid.testInstrumentationRunnerArguments.annotation=com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshot
   ```

   Длительность 3-10 мин. Аннотация отбирает три класса в `app/src/androidTest/.../e2e/screenshots/`, остальные e2e не запускаются.
3. Скопировать PNG из test storage в `.github/images/screenshots/`:

   ```
   ./gradlew :app:copyReadmeScreenshots
   ```

   Task падает, если не найдено ни одного из ожидаемых файлов (game_settings.png, settings.png, script-manager.png).
4. `git status .github/images/screenshots/`. Возможные исходы:
   - Diff пустой - сообщить пользователю, ничего не коммитить, выйти.
   - Diff есть - визуально проверить новые скриншоты (через analyze_image MCP), убедиться что: рендер полный (нет артефактов загрузки), локализация русская, ширина 360 px сохранена.
5. Закоммитить отдельным коммитом:
   - `git add .github/images/screenshots/`
   - сообщение: `Обновить скриншоты в README`. Тело - перечислить изменившиеся файлы и (если очевидно) какая UI-правка к этому привела.

## Ограничения

- `game_settings.png` использует mock-фикстуру `app-page-with-settings-content-realistic.html` - приближение игрового UI без полного игрового CSS/JS. Для точной копии реальной игры локально можно подложить snapshot страницы игры в `refs/game/private/` (gitignored), gradle подхватит его как override фикстуры. Эта поддержка добавляется отдельным шагом, когда в проекте появится локальный snapshot.
- `settings.png` и `script-manager.png` - чисто наш UI приложения, от игры не зависят.

## Когда вызывается

- Шаг 0 в `/release` ДО релизного коммита (см. release.md).
- Вручную, когда меняется UI приложения, чтобы проверить актуальность скриншотов вне релизного цикла.
