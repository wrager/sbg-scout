# Codex и Claude Code

## Общие правила

Открывать корень репозитория `sbg-scout`. Codex читает `AGENTS.md`; `CLAUDE.md` импортирует тот же файл через `@AGENTS.md`. Правила проекта менять в `AGENTS.md`, не копировать между двумя файлами. Личные конфиги и глобальные скиллы для работы с этим репозиторием менять не требуется.

## Окружение Android

- Android SDK Platform 35, Build Tools, Platform Tools (`adb`). Путь к SDK задаётся через `ANDROID_HOME` или локальный `local.properties` с `sdk.dir`; этот файл не коммитится. В Windows для `sdk.dir` удобно использовать прямые слеши.
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
| Релиз указанной версии без push | `$scout-release 1.3.0` | `/scout-release 1.3.0` |
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
