---
name: scout-visual-regression
description: Проверить visual regression SBG Scout или обновить baseline после намеренного изменения UI.
---

# Visual regression SBG Scout

Работать из корня по `AGENTS.md`; окружение описано в `docs/agent-setup.md`, устройство сравнения и пороги — в разделе visual regression в `docs/architecture.md`. В PowerShell использовать `./gradlew.bat` вместо `./gradlew`.

1. Проверить подключение эмулятора: API 33, Pixel 4, образ `default` без Google Play, WebView 101. При нескольких устройствах задать `ANDROID_SERIAL`. Все сценарии используют `FakeGameServer`; реальные игровые действия не выполнять.
2. Для проверки выполнить `./gradlew :app:visualRegressionTest`. Тесты сами фиксируют русскую локаль, тему и часы status bar. Actual PNG находятся в `app/build/outputs/connected_android_test_additional_output/`, triplet `[baseline | actual | highlight]` — в `app/build/reports/visual-regression/`; изучить причину расхождений.
3. При намеренном изменении UI выполнить `./gradlew :app:updateVisualRegressionBaselines`. Задача запускает тесты с `@VisualRegressionTest` и копирует PNG в `.github/images/visual-regression/` в исходном разрешении. Для release bump запускать после изменения версии, поскольку она видна на экране настроек.
4. Проверить `git diff --stat .github/images/visual-regression/` и визуально осмотреть изменённые PNG: экран загружен полностью, часы 12:00, тема соответствует имени файла, диалоги открыты в нужной фазе. Не принимать непреднамеренные изменения UI или артефакты загрузки за новый baseline.
5. После обновления повторить `visualRegressionTest` для проверки воспроизводимости. При превышении порога искать причину нестабильности, не увеличивать tolerance ради прохождения проверки. Приложить изменённые PNG к коммиту с соответствующим изменением UI; скилл сам не коммитит.
