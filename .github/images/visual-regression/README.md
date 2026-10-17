# Visual regression baselines

PNG-файлы в этой папке - эталоны для visual regression тестов. Не редактировать вручную.

## Как обновить

При намеренном UI-изменении:

```
./gradlew :app:updateVisualRegressionBaselines
```

(требует подключённый эмулятор API 33, Pixel 4, WebView 101)

Команда прогоняет тесты с аннотацией `@VisualRegressionTest` и перезаписывает PNG здесь актуальными снимками. Затем `git diff` покажет реально изменившиеся файлы - коммитятся только они.

## Как проверить

```
./gradlew :app:visualRegressionTest
```

Прогоняет те же тесты, но сравнивает actual с PNG в этой папке. При расхождении - красный CI с triplet diff-image (`baseline | actual | highlight`) в `app/build/reports/visual-regression/`.

## Параметры diff

- `VR_MAX_CHANNEL_DIFF = 8` - per-channel tolerance (RGB), компенсирует subpixel anti-aliasing.
- `VR_MAX_DIFFERENT_FRACTION = 0.001` - 0.1% пикселей могут отличаться сверх tolerance, regression сверх этого.

См. `app/build.gradle.kts` (compareVisualRegressionImages).

Baseline сняты на Pixel 4: native-разрешение 1080x2280, density 440 dpi. Высота снимков прокручиваемого содержимого может быть больше высоты экрана.
