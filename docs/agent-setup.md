# Codex и Claude Code

## Общие правила

Открывать корень репозитория `sbg-scout`. Codex читает `AGENTS.md`; `CLAUDE.md` импортирует тот же файл через `@AGENTS.md`. Правила проекта менять в `AGENTS.md`, не копировать между двумя файлами. Личные конфиги и глобальные скиллы для работы с этим репозиторием менять не требуется.

## Окружение Android

- Android SDK Platform 36, Build Tools, Platform Tools (`adb`). Путь к SDK задаётся через `ANDROID_HOME` или локальный `local.properties` с `sdk.dir`; этот файл не коммитится. В Windows для `sdk.dir` удобно использовать прямые слеши.
- JDK 17 — Kotlin toolchain (`app/build.gradle.kts`); JDK 21 — Gradle daemon (`gradle/gradle-daemon-jvm.properties`). Нужен рабочий `java` в PATH или `JAVA_HOME` для старта wrapper. Gradle настроен на автозагрузку toolchain через Foojay; для первого запуска требуется сеть. В офлайн-окружении toolchain и зависимости должны быть подготовлены заранее.
- Node.js 22+ — синхронизация скиллов и загрузчик референсов. Синхронизация использует только стандартную библиотеку, без `npm install`.
- Для e2e и скриншотов — Android Emulator с образом API 33 `default` (без Google Play), Pixel 4, WebView 101. Такой профиль используется в `.github/workflows/e2e.yml`. Проверить `adb devices` и `adb shell dumpsys webviewupdate`; при нескольких устройствах задать `ANDROID_SERIAL`.

`RELEASE_STORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD` нужны для подписанного релиза в CI. Для обычной локальной проверки release-сборки секреты не требуются: без `RELEASE_STORE` APK будет неподписанным. Секреты и keystore в репозиторий не добавлять.

## Команды

PowerShell:

```powershell
node scripts/syncAgentSkills.mjs --check
node --test scripts/syncAgentSkills.test.mjs
./gradlew.bat --version
./gradlew.bat ktlintCheck detekt lintInstr testInstrUnitTest assembleDebug assembleInstr assembleRelease
./gradlew.bat connectedInstrAndroidTest
./gradlew.bat :app:updateReadmeScreenshots
```

Bash (Linux/macOS):

```sh
node scripts/syncAgentSkills.mjs --check
node --test scripts/syncAgentSkills.test.mjs
./gradlew --version
./gradlew ktlintCheck detekt lintInstr testInstrUnitTest assembleDebug assembleInstr assembleRelease
./gradlew connectedInstrAndroidTest
./gradlew :app:updateReadmeScreenshots
```

`testInstrUnitTest` запускает JVM-тесты без эмулятора. E2e требуют подключённого эмулятора и используют `FakeGameServer`; не запускать автоматизированные проверки против реальной игры. При отсутствии JDK/SDK/эмулятора агент сообщает, какие проверки не выполнены и почему.

## Один источник скиллов

```text
.agents/skills/scout-*/     <- редактируемые исходники, обнаруживаются Codex
.claude/skills/scout-*/     <- генерируемые копии, обнаруживаются Claude Code
scripts/syncAgentSkills.mjs <- синхронизация и проверка копий
```

Копии коммитятся: свежий clone сразу содержит скиллы для обоих агентов. Симлинки и junction не используются, поэтому не нужны Developer Mode, права администратора или особые Git-настройки Windows. `CLAUDE.md` использует поддерживаемый импорт; агентам не требуется угадывать, какой файл читать.

| Назначение | Codex | Claude Code |
|---|---|---|
| Сборка, lint, unit-тесты, e2e | `$scout-verify` | `/scout-verify` |
| Три скриншота README | `$scout-screenshots` | `/scout-screenshots` |
| Подготовка release PR без push | `$scout-release` | `/scout-release` |
| Текст анонса с базовой версии | `$scout-announce 1.2.0` | `/scout-announce 1.2.0` |

Скиллы также могут выбираться по описанию задачи. Аргументы берутся из сообщения пользователя: общие `SKILL.md` не используют специфичные для Claude `$ARGUMENTS` и названия его инструментов. Между сценариями агент переходит чтением общего `SKILL.md`, поэтому наличие slash-команд не требуется.

Для нового скилла создать `.agents/skills/scout-<имя>/SKILL.md` с YAML-полями `name` (совпадает с именем каталога, до 64 символов) и `description`. При необходимости добавить `scripts/`, `references/`, `assets/`; синхронизатор копирует всё дерево, включая бинарные файлы. Ссылки на файлы проекта писать от корня репозитория; локальные ресурсы скилла — относительно `SKILL.md`.

После добавления, изменения или удаления исходников:

```sh
node scripts/syncAgentSkills.mjs
node scripts/syncAgentSkills.mjs --check
node --test scripts/syncAgentSkills.test.mjs
```

Коммитить исходники и копии вместе. Маркер `.generated` обозначает управляемые каталоги Claude: синхронизация заменяет их содержимое и удаляет копии удалённых скиллов. Ручные правки в копиях будут перезаписаны. Посторонние скиллы не затрагиваются; при конфликте с неуправляемым каталогом синхронизатор останавливается. `--check` ничего не записывает и возвращает ненулевой код при расхождении; CI запускает эту проверку отдельно от Android-сборки.

После добавления скиллов начать новую сессию Codex/Claude, если список не обновился. В уже открытом чате агент может прочитать нужный `.agents/skills/scout-*/SKILL.md` вручную.

Основание для путей и формата: [официальная документация OpenAI о скиллах](https://learn.chatgpt.com/docs/build-skills), [скиллы Claude Code](https://code.claude.com/docs/en/skills), [импорт общих инструкций в Claude Code](https://code.claude.com/docs/en/memory#share-one-file-with-other-coding-tools).

## PR и релизы

Заголовок PR должен быть Conventional Commit: например, `feat: новая настройка`,
`fix(updater): исправить загрузку APK`, `ci: обновить проверки`.
Scope необязателен, описание может быть русским. Проверяется заголовок PR;
внутренние коммиты переписывать не требуется. В `main` используется только
squash с заголовком PR и пустым телом. Breaking change отмечать `!` в
заголовке (`feat!: ...`), пояснение оставлять в описании PR.

`feat` увеличивает MINOR, `fix` и `perf` — PATCH, breaking change — MAJOR.
Обычные `chore`, `ci`, `docs`, `build`, `test`, `refactor`, `style`
без breaking change сами по себе не создают релиз.
Renovate использует semantic commit messages.

### Debug APK в PR

После успешного Android job `check` CI сохраняет ровно
`app/build/outputs/apk/debug/app-debug.apk` через actions/upload-artifact.
Отдельный `preview-comment` добавляет ссылку на ZIP, SHA ветки и merge commit,
run/attempt. Сборка имеет только contents: read; job комментария —
pull-requests: write, без checkout, скачивания и исполнения кода PR.
Для fork/Dependabot и push main preview не публикуется.

Имена artifact и message-id включают run/attempt: один комментарий на попытку,
старые остаются историей. Сбой новой сборки не добавляет комментарий.
Rerun старого run не заменяет ссылку нового. Artifact хранится 14 дней,
если run/artifact не удалены раньше; для скачивания нужен вход в GitHub.

Распаковать ZIP и установить app-debug.apk. Debug устанавливается рядом со stable.
Общий debug-keystore не используется: при несовпадении сертификатов CI-сборок
потребуется переустановка debug-приложения. Статусы e2e и других jobs
смотреть отдельно.

### Подготовка и публикация

1. После push в `main` workflow `Release` запускает release-please.
   Он поддерживает отдельный release PR с версией в `version.txt`,
   `.release-please-manifest.json` и трёх числах Gradle, обновляемых по
   маркерам `x-release-please-major/minor/patch`. Формула Android `versionCode`
   прежняя: `major * 10000 + minor * 100 + patch`, minor и patch меньше 100.
   `CHANGELOG.md` не создаётся; автоматический черновик notes находится в теле PR.
2. Вызвать `scout-release` для этого PR: сверить notes с итоговым кодом,
   записать русские `RELEASE_NOTES.md`, после bump версии сгенерировать три PNG,
   проверить изображения и выполнить проверки. CI запускает
   `node scripts/checkRelease.mjs`: заголовок notes должен совпадать с version.txt
   и manifest. Это не проверка полноты текста; её обеспечивает review diff. Служебную структуру тела PR,
   заголовок и метки release-please сохранить. Перед merge повторить сверку,
   если бот изменил PR. Notes, версия и PNG входят в один итоговый squash.
3. Вручную squash-merge подготовленный release PR. Workflow создаёт тег
   `vX.Y.Z` и draft GitHub Release, затем job `publish` проверяет наличие
   трёх signing secrets, делает checkout SHA релиза, собирает и проверяет
   подпись через `apksigner verify`, versionName и versionCode готового APK против тега. APK загружается как `sbg-scout-vX.Y.Z.apk`.
   Только после загрузки softprops v3 публикует draft с текстом из
   `RELEASE_NOTES.md` и помечает его latest. Отдельный push тега не запускает выпуск.

Если `publish` упал, выбрать **Re-run failed jobs** либо повторить именно
`publish` в исходном run. Не запускать весь workflow заново ради восстановления:
успешный job release-please уже передал SHA и тег, повторный расчёт может не
вернуть `release_created`. Уже загруженный APK не перезаписывается
(`overwrite_files: false`); повтор завершает тот же draft. При повреждённом
asset или другом состоянии, требующем ручного ремонта, сначала проверить
draft через GitHub UI/CLI; не удалять теги и не публиковать draft без готового APK.

### Настройки GitHub и первое включение

- Разрешить только squash merge, squash title = **PR title**, тело = **Blank**.
- Защитить `main`: PR обязателен, обход для администраторов отключён,
  обязательны постоянные checks `check`, `agent-skills`, `pr-title`.
  Условный e2e с path filters обязательным не назначать.
- В Actions → General включить **Allow GitHub Actions to create and approve
  pull requests**, сохранив default token permissions = read. Workflows выдают
  права записи только нужным jobs.
- Первый release PR от `GITHUB_TOKEN` может потребовать **Approve workflows
  to run**. Это возможно и после обновления ботом. Проверить появление всех required checks перед merge. Отдельный
  GitHub App/token для этого процесса пока не нужен.

Начальная версия миграции — `1.2.0`, bootstrap — коммит тега `v1.2.0`
`aa21dc510342d33987250c4aad2147090f29db8b`. Старую историю не переписывать.
При первом release PR вручную проверить все изменения с этого тега:
non-conventional сообщения могут отсутствовать в автоматических notes.
Если требуется иной первый bump, использовать штатный одноразовый
`Release-As: X.Y.Z` в теле итогового squash-коммита миграционного PR:
вручную добавить этот footer при merge вместо пустого тела по умолчанию.
Footer должен попасть в main; один лишь внутренний коммит ветки не подходит.
Далее вернуться к автоматическому расчёту и пустому squash-телу.

При внедрении без выпуска релиза доступны actionlint, локальная проверка
release-please и реальный PR с debug APK. Создание первого release PR ботом,
разрешение его CI, публикацию draft и rerun publish на GitHub проверить
при отдельно запрошенном выпуске.

Основания: [release-please](https://github.com/googleapis/release-please),
[release-please action](https://github.com/googleapis/release-please-action),
[softprops v3](https://github.com/softprops/action-gh-release/tree/v3.0.3).
