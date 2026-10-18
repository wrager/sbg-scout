# Codex и Claude Code

## Общие правила

Открывать корень репозитория `sbg-scout`. Codex читает `AGENTS.md`; `CLAUDE.md` импортирует тот же файл через `@AGENTS.md`. Правила проекта менять в `AGENTS.md`, не копировать между двумя файлами. Личные конфиги и глобальные скиллы для работы с этим репозиторием менять не требуется.

## Быстрый старт в Windows

Общие команды человека, Codex и Claude Code находятся в `scripts/android.ps1`.
Android Studio не требуется. Нужны Windows PowerShell 5.1+, Git и Node.js 22+.
Из корня репозитория первоначальная установка выполняется так:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/setupAndroid.ps1
```

Установщик проверяет контрольные суммы архивов Temurin JDK 21/17 и Command-Line
Tools 23. Через Android CLI устанавливает Platform 37, Build Tools 36.0.0,
Platform Tools, Emulator и образ API 33 `default` x86_64 revision 2; создаёт AVD
`sbg-scout-api33` (Pixel 4). Нужны сеть, принятие лицензии SDK и место для распаковки,
данных эмулятора и кешей. Повторный запуск сохраняет существующий AVD.

Каталог инструментов: `%USERPROFILE%\AppData\Local\SBGScout` (`jdk17`, `jdk21`,
`sdk`, `gradle`, `android-user`, `avd`, `logs`). Он находится вне Git. У установщика,
командного скрипта и скрипта окружения есть `-ToolsRoot` для другого каталога.

Сначала выполнить `android.ps1 doctor` (команда ниже). Если Emulator сообщает
`WHPX ... is installed and usable`, менять компоненты Windows не нужно, даже если
список optional features показывает `HypervisorPlatform` как выключенный.
Если ускорение недоступно, включить **Windows Hypervisor Platform** из PowerShell
с правами администратора (только этот скрипт требует UAC):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/enableAndroidHypervisor.ps1
```

Результат: `%USERPROFILE%\AppData\Local\SBGScout\logs\hypervisor.txt`.
Если `RestartNeeded: True`, перезагрузить Windows в удобный момент. Скрипт сам
компьютер не перезагружает. Затем `doctor` должен подтвердить доступность WHPX.

```powershell
# Java, SDK, AVD, Gradle и ускорение эмулятора
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 doctor
# APK: app/build/outputs/apk/debug/app-debug.apk
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 build
# Собрать, открыть эмулятор с окном, установить и запустить debug-приложение
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 run -ShowWindow
# Smoke e2e через FakeGameServer; эмулятор запускается автоматически без окна
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 test
# Все e2e или указанный класс
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 test -AllTests
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 test -TestClass com.github.wrager.sbgscout.e2e.flows.smoke.GameLoadsSmokeTest
# Полная проверка CI (lint, JVM-тесты, debug/instr/release)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 verify
# Последние 2000 строк logcat в logs/logcat.txt; остановка только нашего AVD
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 logs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 stop
```

`emulator` запускает только виртуальное устройство. По умолчанию окно скрыто;
`-ShowWindow` показывает его при новом запуске. Для уже запущенного без окна AVD
выполнить `stop`, затем `emulator -ShowWindow`. Ожидание загрузки ограничено
180 секундами (`-BootTimeoutSeconds`). Выделены 2 ядра и 2 ГБ RAM; используется
программный SwiftShader с отключённым Vulkan. Порт 5580 резервируется для этого AVD;
при конфликте скрипт останавливается. Подключённые телефоны и другие AVD не выбираются.
`stop` штатно завершает доступный AVD через adb. Если AVD завис в `offline`,
он завершает только дерево сохранённого процесса, сверяя PID и время запуска;
без подтверждённого владельца чужие процессы не останавливаются.
Перед e2e проверяются API 33 и WebView 101; при несовпадении тесты не запускаются.

Debug использует настоящий адрес игры. Для автоматической проверки запуска агентом
применять `run -Offline`: Wi-Fi и мобильные данные **на нашем эмуляторе** отключаются
до запуска Activity. Вернуть сеть можно через Wi-Fi в настройках Android.
Игровой UI проверять через `test` с `FakeGameServer`. Скрипты не задают GPS,
не входят в аккаунт и не выполняют игровых действий.

### Доступ из Codex и проверка в Claude Code

Скрипты сами задают окружение процесса, поэтому изменение глобального PATH,
открытие Android Studio и создание `local.properties` не нужны. Для прямых команд
в текущем PowerShell: `. ./scripts/androidEnvironment.ps1`, затем `./gradlew.bat --version`.
Для сборок предпочитать `android.ps1`: он также передаёт Gradle путь к JDK 17 и
ограничивает число workers до двух. Эмулятор получает 2 ГиБ RAM, snapshots отключены;
это уменьшает потребление памяти и диска на машине с 16 ГиБ RAM. Gradle запускается
с `--no-daemon`, Kotlin компилируется в его процессе: после команды не остаются
долгоживущие JVM, удерживающие память и перенаправленный вывод PowerShell.

В Codex с `workspace-write` каталог инструментов лежит вне разрешённой области.
При отказе агент использует штатный запрос выполнения с доступом к хосту
(`require_escalated`), не меняя ACL и не отключая песочницу. Это же необходимо
для долгоживущих `adb`/Emulator: MXC может завершать фоновые дочерние процессы после
окончания команды. Доступ к хосту не означает права администратора Windows.
Нужны запись в каталог SBGScout, сеть для зависимостей и локальные соединения с adb.
Одобрения зависят от политики агента; скрипты их не обходят.

После установки проверить из **новой сессии Claude Code** в корне репозитория:

1. Прочитать `AGENTS.md` и этот файл, выполнить `android.ps1 doctor`, затем `build`.
2. Выполнить `android.ps1 emulator`; отдельной командой вызвать `logs`, проверив,
   что эмулятор сохранился после окончания первой команды.
3. Выполнить `android.ps1 run -Offline`, затем `test`, затем `stop`.
4. Сообщить путь к APK, результат smoke e2e и запросы разрешений/ошибки доступа.
   Не считать проверку успешной, если команды пришлось выполнять человеку вручную.

В Bash Claude Code использовать полный вызов `powershell.exe -NoProfile
-ExecutionPolicy Bypass -File scripts/android.ps1 <команда>`. Если политика Claude
блокирует команду, показать точный отказ; не отключать проверки разрешений глобально.
Авторизация в игре не требуется.

Основания: [SDK CLI](https://developer.android.com/tools/sdkmanager),
[ускорение эмулятора](https://developer.android.com/studio/run/emulator-acceleration),
[Windows sandbox Codex](https://learn.chatgpt.com/docs/windows/windows-sandbox).

## Окружение Android

- Android SDK Platform 37, Build Tools, Platform Tools (`adb`). Путь к SDK задаётся через `ANDROID_HOME` или локальный `local.properties` с `sdk.dir`; этот файл не коммитится. В Windows для `sdk.dir` удобно использовать прямые слеши.
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
| Проверка UI по baseline | `$scout-visual-regression` | `/scout-visual-regression` |
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
squash с заголовком и описанием PR; соавторов в описание добавляет агент по AGENTS.md. Breaking change отмечать `!` в
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
2. Вызвать `scout-release` для этого PR: выполнить `node scripts/updateBundledSvp.mjs`
   до тестов и скриншотов, включить обновлённый
   `app/src/main/assets/scripts/sbg-vanilla-plus.user.js` в этот же PR; сверить notes с итоговым кодом,
   записать русские `RELEASE_NOTES.md`, после bump версии сгенерировать три README-PNG
   и обновить visual regression baseline через `scout-visual-regression`,
   проверить изображения и выполнить проверки. CI запускает
   `node scripts/checkRelease.mjs`: заголовок notes должен совпадать с version.txt
   и manifest. Это не проверка полноты текста; её обеспечивает review diff. Служебную структуру тела PR,
   заголовок и метки release-please сохранить. Перед merge повторить сверку,
   если бот изменил PR. Перед merge выполнить `node scripts/updateBundledSvp.mjs --check`:
   даже при неизменном HEAD мог выйти новый SVP. Если бандл устарел — повторить
   обновление, тесты и скриншоты. Notes, версия, PNG и бандл входят в один итоговый squash.
3. Вручную squash-merge подготовленный release PR. Workflow создаёт тег
   `vX.Y.Z` и draft GitHub Release, затем job `publish` проверяет наличие
   трёх signing secrets, делает checkout SHA релиза и проверяет актуальность бандла SVP
   через `node scripts/updateBundledSvp.mjs --check` до сборки и перед публикацией.
   Ошибка сети или несовпадение с последним стабильным релизным артефактом
   останавливает выпуск без подмены закоммиченных файлов на runner. Job собирает и проверяет
   подпись через `apksigner verify`, versionName и versionCode готового APK против тега. APK загружается как `sbg-scout-vX.Y.Z.apk`.
   Только после загрузки softprops v3 публикует draft с текстом из
   `RELEASE_NOTES.md` и помечает его latest. Отдельный push тега не запускает выпуск.

Обновление SVP использует только GitHub Release asset: проверяет userscript-заголовок,
версию против тега, размер и SHA-256 (если API возвращает digest). Команда не берёт
исходники из соседнего checkout или `main`. Для API можно задать `GITHUB_TOKEN`;
токен передаётся только в запрос метаданных, публичный asset скачивается без него.
Тесты загрузчика без сети: `node --test scripts/updateBundledSvp.test.mjs`.

Если SVP обновился после merge release PR и `publish` остановился на проверке
актуальности, повтор job не исправит старый бандл. Подготовить следующий release PR
со свежим SVP и полными проверками; прежний draft оставить неопубликованным.
Тег и SHA уже созданного релиза не переписывать. Если устаревание обнаружено
до merge, достаточно обновить текущий release PR.

Если `publish` упал по другой причине, выбрать **Re-run failed jobs** либо повторить именно
`publish` в исходном run. Не запускать весь workflow заново ради восстановления:
успешный job release-please уже передал SHA и тег, повторный расчёт может не
вернуть `release_created`. Уже загруженный APK не перезаписывается
(`overwrite_files: false`); повтор завершает тот же draft. При повреждённом
asset или другом состоянии, требующем ручного ремонта, сначала проверить
draft через GitHub UI/CLI; не удалять теги и не публиковать draft без готового APK.

### Настройки GitHub и первое включение

- Разрешить только squash merge, squash title = **PR title**, тело = **PR description**.
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
явно указать этот footer в описании миграционного PR перед squash.
Footer должен попасть в main; один лишь внутренний коммит ветки не подходит.
Далее вернуться к автоматическому расчёту; squash продолжает переносить описание PR и соавторство.

При внедрении без выпуска релиза доступны actionlint, локальная проверка
release-please и реальный PR с debug APK. Создание первого release PR ботом,
разрешение его CI, публикацию draft и rerun publish на GitHub проверить
при отдельно запрошенном выпуске.

Основания: [release-please](https://github.com/googleapis/release-please),
[release-please action](https://github.com/googleapis/release-please-action),
[softprops v3](https://github.com/softprops/action-gh-release/tree/v3.0.3).
