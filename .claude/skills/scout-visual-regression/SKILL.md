---
name: scout-visual-regression
description: Проверить visual regression SBG Scout или обновить baseline после намеренного изменения UI.
---

# Visual regression SBG Scout

Работать из корня по `AGENTS.md`; окружение описано в `docs/agent-setup.md`, устройство сравнения и пороги — в разделе visual regression в `docs/architecture.md`. В PowerShell использовать `./gradlew.bat` вместо `./gradlew`.

1. До изменения UI перечислить затронутые состояния и переходы: экран, светлая и тёмная тема, локаль, флаги, фазы загрузки и анимации. Для каждого найти тест с `@VisualRegressionTest` в `app/src/androidTest/` и его PNG в `.github/images/visual-regression/`. Если нужной пары нет, добавить тест и baseline в том же коммите; существующий список baseline не является полным перечнем UI. Движение анимации проверить отдельным e2e-утверждением, поскольку PNG показывает одну фазу.
2. Проверить подключение эмулятора: API 33, Pixel 4, образ `default` без Google Play, WebView 101. При нескольких устройствах задать `ANDROID_SERIAL`. Все сценарии используют `FakeGameServer`; реальные игровые действия не выполнять.
3. Для проверки выполнить `./gradlew :app:visualRegressionTest`. Тесты сами фиксируют русскую локаль, тему и часы status bar. Actual PNG находятся в `app/build/outputs/connected_android_test_additional_output/`, triplet `[baseline | actual | highlight]` — в `app/build/reports/visual-regression/`; изучить причину расхождений.
4. При намеренном изменении UI выполнить `./gradlew :app:updateVisualRegressionBaselines`. Задача запускает тесты с `@VisualRegressionTest` и копирует PNG в `.github/images/visual-regression/` в исходном разрешении. В Prepare release причина обновления — намеренное изменение видимой версии Scout/SVP; запускать после bump. Сам факт выпуска не разрешает принимать остальные различия: просмотреть каждый изменённый PNG в diff.
5. Проверить `git diff --stat .github/images/visual-regression/` и визуально осмотреть изменённые PNG: экран загружен полностью, часы 12:00, тема соответствует имени файла, диалоги открыты в нужной фазе. Не принимать непреднамеренные изменения UI или артефакты загрузки за новый baseline.
6. После обновления повторить `visualRegressionTest` для проверки воспроизводимости. При превышении порога искать причину нестабильности, не увеличивать tolerance ради прохождения проверки. Приложить изменённые PNG к коммиту с соответствующим изменением UI; скилл сам не коммитит.
