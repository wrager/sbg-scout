# Выбор проверок

CI и агенты используют `scripts/planChecks.mjs`. Получить план без SDK и эмулятора:

```sh
node scripts/planChecks.mjs --base origin/main
```

Выполнить команды из `commands`; в PowerShell заменить `./gradlew` на `./gradlew.bat`.
База должна совпадать с базой задачи/PR. Учитываются все коммиты с merge-base, staged,
unstaged и новые неигнорируемые файлы. После изменения исходников скиллов сначала
выполнить `node scripts/syncAgentSkills.mjs`, затем получить план.

Функциональный e2e в Windows выполнять через `scripts/android.ps1 test -AllTests`, а не
командой `connectedInstrAndroidTest` из плана: на одном эмуляторе QEMU не освобождает
память после тестов и исчерпывает память Windows (подробности в `agent-setup.md`).
<!-- 🤖 Generated with Claude Opus 5.5 -->

Затронутый набор проверок выполнить один раз. Только новые и изменённые e2e-тесты
и тесты с состоянием повторить 5-10 раз подряд с зелёным результатом каждого прогона.
Для повторов выбирать конкретные сценарии фильтром тестового раннера: наличие
одного такого сценария не требует многократного запуска всех неизменённых тестов файла
или набора. После фикса снова выполнить упавшую проверку и проверки, затронутые фиксом.
<!-- 🤖 Generated with GPT-6.1 Sol -->

| Изменения | Проверки |
|---|---|
| `README.md`, `docs/**`, `AGENTS.md`, `CLAUDE.md`, README-PNG, issue templates | Нет исполняемых проверок; проверить смысл и diff |
| `.agents/skills/**`, `.claude/skills/**` | `syncAgentSkills.mjs --check` |
| `scripts/syncAgentSkills*.mjs` | Синхронизация + тесты синхронизатора + синтаксис Node |
| `scripts/checkRelease*.mjs` | Релизные файлы + тесты валидатора + синтаксис Node |
| `scripts/updateBundledSvp*.mjs` | Тесты загрузчика + синтаксис Node, без сети |
| `RELEASE_NOTES.md`, `version.txt`, `.release-please-manifest.json` | Согласованность релизных файлов |
| `scripts/releaseNotes*.mjs`, `scripts/prepareRelease.mjs`, `.github/release-notes.json`, `.release-notes.json` | Тесты процесса notes + синтаксис Node/JSON |
| `.github/RELEASE_NOTES_TEMPLATE.md` | Тесты процесса notes |
| `release-please-config.json` | Релизные файлы, тесты валидатора, синтаксис JSON |
| `renovate.json`, `scripts/fetchRefs.mjs`, `scripts/checkSyntax.mjs` | Синтаксис JSON и Node-скриптов |
| `scripts/*.ps1` | Парсинг PowerShell и `android.test.ps1`, без SDK/эмулятора |
| `app/src/main/**`, `app/src/instr/**` | Полный Android-набор + функциональный e2e + VR |
| `app/src/test/**` | ktlint, detekt, Android Lint, JVM-тесты; без APK и эмулятора |
| `app/src/androidTest/**` | ktlint, detekt, Android Lint, функциональный e2e + VR; без отдельных debug/release APK |
| `app/src/debug/**` | ktlint, detekt, debug APK |
| `app/src/release/**`, `app/proguard-rules.pro` | ktlint, detekt, release APK |
| `.github/images/visual-regression/*.png` | Только VR |
| Общие Gradle-файлы, `gradle/**`, wrapper, `buildSrc/**`, `build-logic/**` | Полный Android-набор + e2e + VR |
| `.editorconfig` / `detekt.yml` | ktlint / detekt |
| `.github/workflows/e2e.yml`, `scripts/logRunnerMemory.sh` | actionlint + тесты планировщика/команд workflow + функциональный e2e + VR |
| `.github/workflows/prepare-release.yml` | actionlint + тесты планировщика/release/SVP + функциональный e2e + VR |
| `.github/workflows/release.yml`, `.github/workflows/release-notes.yml` | actionlint + релизные файлы и тесты release/SVP |
| `.github/workflows/pr-title.yml` | actionlint |
| `ci.yml`, `planChecks*.mjs`, неизвестные пути | Полный набор без сетевой проверки SVP |

Для смешанного diff наборы объединяются. При удалении или переименовании учитывается
старый путь. Обновление встроенного userscript считается изменением приложения,
а не обычного Node-инструмента. Любой ресурс `main` консервативно включает весь Android-набор.

Полный Android-набор: `ktlintCheck detekt lintInstr testInstrUnitTest assembleDebug assembleInstr assembleRelease`.
`testInstrUnitTest` — JVM-тесты, не инструментальные. `lintInstr` проверяет instr-вариант.

## Полная проверка и релиз

```sh
node scripts/planChecks.mjs --full
node scripts/planChecks.mjs --release-pr
```

`--full` включает все локальные группы, кроме сетевой сверки SVP. `--release-pr`
включает и её; в CI release PR распознаётся по ветке `release-please--branches--main`
и получает полный набор даже при изменении только notes. Правила подготовки
релиза, скриншотов и baseline остаются в `scout-release`.

`scripts/android.ps1 verify` остаётся явной полной проверкой Android + локальных
Node/PowerShell-инструментов. Для workflows дополнительно нужен actionlint 1.7.12:
`actionlint -shellcheck= -pyflakes=`. CI устанавливает эту фиксированную версию.
Группа `windows` выполняется на Windows PowerShell 5.1; на других ОС её нужно
проверить в Windows CI и явно указать отсутствие локального прогона.
Парсер проверяет YAML и выражения Actions; выполнение bash/PowerShell и внешних сервисов
нужно проверять соответствующим запуском. Node-тесты планировщика используют временные Git-репозитории.

## Особенности CI

- PR сравнивается по merge-base base/head SHA, а не только по последнему коммиту.
  Push в main сравнивается по before/after; при нулевом before выбирается полный набор.
  Пагинации API и ограничения списка путей `on.paths` здесь нет.
- `check` и `agent-skills` — прежние required checks. Ненужный job пропускается;
  ошибка/отсутствие плана вызывает его падение. Сам workflow CI не фильтруется по путям.
- `agent-skills` сохраняет имя для branch protection, но его шаги release/SVP/skills
  выбираются независимо. Переименовывать required checks в настройках GitHub не нужно.
- Только выбранная debug-сборка создаёт preview APK и комментарий. Комментарий
  сообщает о выбранных Android-проверках, а статусы остальных jobs смотрятся отдельно.
- E2e вызывается из CI только для PR. Ручной запуск `e2e.yml` выполняет e2e и VR.
  Функциональный набор исключает VR-аннотацию и делится на четыре шарда
  (`numShards`/`shardIndex`) в matrix job `functional`; VR сравнивает PNG в отдельном
  job `visual`. У каждого job свой эмулятор: SwiftShader в QEMU не освобождает память
  после тестов, и весь набор на одном эмуляторе исчерпывал 16 ГБ раннера. Локальный
  запуск требует окружения из `agent-setup.md`.
  <!-- 🤖 Generated with Claude Opus 5.5 -->
- Gradle-команды e2e запускаются через `scripts/logRunnerMemory.sh`: каждые 30 секунд
  в лог job выводится строка `runner-memory` с занятой и доступной памятью, swap,
  свободным местом на диске и RSS процессов QEMU. Если раннеру не хватит памяти и он
  оборвётся с `The runner has received a shutdown signal` без отчётов тестов, эти строки
  покажут рост до момента обрыва.
  <!-- 🤖 Generated with Claude Opus 5.5 -->
- `pr-title` нужен всем PR: заголовок задаёт squash-коммит и влияет на release-please.
  Release workflow остаётся на каждом push main; публикация — только при создании релиза.

Ошибка определения diff не означает пустой план. Исправить базу/доступ к Git либо
явно выполнить `--full`; недоступные проверки перечислять с точной причиной.

🤖 Generated with GPT-6.1 Sol
