---
name: my-sbg-verify
description: Проверить изменения SBG Scout перед коммитом или найти причину сбоя Android-сборки, JVM-тестов и e2e.
---

# Проверка SBG Scout

Работать из корня репозитория по `AGENTS.md`. Подготовка JDK/SDK и эмулятора описана в `docs/agent-setup.md`. В Bash использовать `./gradlew`, в PowerShell — `./gradlew.bat` с теми же аргументами.

1. Проверить `git status --short`, затем определить затронутые области по diff, включая новые файлы.
2. Проверить общие скиллы и их синхронизатор:

   ```text
   node scripts/syncAgentSkills.mjs --check
   node --test scripts/syncAgentSkills.test.mjs
   ```

3. Перед коммитом выполнить полный набор из CI:

   ```sh
   ./gradlew ktlintCheck detekt lintInstr testInstrUnitTest assembleDebug assembleInstr assembleRelease
   ```

   `testInstrUnitTest` — JVM-тесты без эмулятора. В проекте установлен `testBuildType = "instr"`; не заменять задачу на `testDebugUnitTest`. Для точечной проверки JVM-класса можно использовать `testInstrUnitTest --tests 'полное.ИмяКласса'`.

4. При изменении `androidTest/` или поведения, покрываемого e2e, запустить локально `connectedInstrAndroidTest` до push. Для точечной проверки:

   ```sh
   ./gradlew connectedInstrAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.github.wrager.sbgscout.e2e.flows.smoke.GameLoadsSmokeTest"
   ```

   Подставить полное имя изменённого теста. E2e идут через `FakeGameServer` на loopback, без обращений к игре. Эмулятор: API 33, Pixel 4, WebView 101, образ `default` без Google Play.

5. При падении исправить причину и повторить затронутые проверки. Не отключать lint/тесты ради зелёного результата.
6. Сообщить результаты и ограничения: какие команды прошли, какие упали, какие не запускались и почему. Если нет JDK/SDK/эмулятора или сети для зависимостей, выполнить доступные проверки и явно обозначить оставшиеся. Коммит, тег и push сами по себе не входят в этот скилл.
