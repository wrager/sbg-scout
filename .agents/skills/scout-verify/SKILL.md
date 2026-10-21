---
name: scout-verify
description: Выбрать и выполнить проверки изменений SBG Scout перед коммитом, диагностировать Android-сборку, JVM-тесты и e2e.
---

# Проверка SBG Scout

Работать из корня по `AGENTS.md`. Матрица проверок — `docs/ci-checks.md`, подготовка JDK/SDK и эмулятора — `docs/agent-setup.md`.

1. Проверить `git status --short` и весь diff задачи. При изменении `.agents/skills/` сначала выполнить `node scripts/syncAgentSkills.mjs`.
2. Получить общий с CI план: `node scripts/planChecks.mjs --base origin/main`. При другой базе PR указать её вместо `origin/main`. План учитывает коммиты, staged/unstaged и новые файлы. Для подготовки релиза использовать `--release-pr`; для явно запрошенной полной проверки — `--full`.
3. Выполнить команды из `commands`. Для обычной документации пустой план нормален: Android SDK не нужен. Ошибку определения diff исправить или перейти на `--full`; не трактовать её как разрешение пропустить проверки. В PowerShell использовать `./gradlew.bat` вместо `./gradlew`; окружение готовит `scripts/androidEnvironment.ps1`.
4. Если выбраны e2e или изменилось поведение, покрываемое e2e, проверить локально до push. Для отдельного flow минимум — изменённый/новый класс:

   `./gradlew connectedInstrAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.github.wrager.sbgscout.e2e.flows.smoke.GameLoadsSmokeTest"`

   Подставить нужный класс. Для общей инфраструктуры/POM выполнить полный затронутый набор; если выбрана visual regression, выполнить `./gradlew visualRegressionTest`. Полный функциональный прогон из плана исключает VR-аннотацию: VR выполняется отдельной командой без дублирования. Эмулятор: API 33, Pixel 4, WebView 101, образ `default` без Google Play; только FakeGameServer на loopback. Не запускать разные worktree одновременно на одном AVD; изоляция AVD/портов и короткий путь Windows описаны в `docs/agent-setup.md`.
5. При падении исправить причину и повторить затронутые проверки. Сообщить фактические результаты, недоступные проверки и причины. Коммит, тег и push сами по себе не входят в скилл.

`testInstrUnitTest` — JVM-тесты без эмулятора. Из-за `testBuildType = "instr"` не заменять их на `testDebugUnitTest`. Точечный JVM-прогон: `testInstrUnitTest --tests 'полное.ИмяКласса'`; `--tests` не применяется к инструментальным тестам.
