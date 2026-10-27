import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    jacoco
}

jacoco {
    toolVersion = "0.8.15"
}

val readmeScreenshotMode =
    gradle.startParameter.taskNames.any {
        it.substringAfterLast(":") == "updateReadmeScreenshots"
    }

android {
    namespace = "com.github.wrager.sbgscout"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.wrager.sbgscout"
        minSdk = 26
        targetSdk = 35
        val versionMajor = 1 // x-release-please-major
        val versionMinor = 3 // x-release-please-minor
        val versionPatch = 0 // x-release-please-patch
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Вместе с orchestrator + enableAndroidTestCoverage = true: storage service
        // пишет coverage в scoped storage, orchestrator не очищает его при
        // clearPackageData, и AGP скачивает .ec с устройства в
        // build/outputs/code_coverage/instrAndroidTest/connected/. Без этого
        // coverage-файлы теряются между тестами.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"

        /*
         * AGP не позволяет задавать testInstrumentationRunnerArguments отдельно
         * для задач, поэтому режим README-снимков выбирается до конфигурации теста.
         * 🤖 Generated with GPT-6 Sol
         */
        if (readmeScreenshotMode) {
            testInstrumentationRunnerArguments["annotation"] =
                "com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshot"
        }

        // Аналогично для visual regression: `./gradlew visualRegressionTest`
        // и `./gradlew updateVisualRegressionBaselines` фильтруют прогон по
        // @VisualRegressionTest. Тесты пишут actual.png в test storage; разница
        // между задачами - в host-side finalizer (compare vs copy), а не в
        // device-side поведении, поэтому отдельный record-флаг не нужен.
        val isVisualRegression =
            gradle.startParameter.taskNames.any {
                val name = it.substringAfterLast(":")
                name == "visualRegressionTest" || name == "updateVisualRegressionBaselines"
            }
        if (isVisualRegression) {
            testInstrumentationRunnerArguments["annotation"] =
                "com.github.wrager.sbgscout.e2e.visualregression.VisualRegressionTest"
        }

        buildConfigField("String", "GAME_APP_URL", "\"https://sbg-game.ru/app\"")
        buildConfigField("String", "GAME_LOGIN_URL", "\"https://sbg-game.ru/login\"")
        buildConfigField("String", "GAME_HOST_MATCH", "\"sbg-game.ru\"")
    }

    signingConfigs {
        create("release") {
            val storeBase64 = System.getenv("RELEASE_STORE")
            if (storeBase64 != null) {
                val storeFile = File(layout.buildDirectory.asFile.get(), "release.jks")
                storeFile.parentFile.mkdirs()
                storeFile.writeBytes(Base64.getDecoder().decode(storeBase64))
                this.storeFile = storeFile
            }
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = "release"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("instr") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instr"
            versionNameSuffix = "-instr"
            matchingFallbacks += listOf("debug")
            // BuildConfig для instr указывает на localhost (реальный порт подставляется
            // в androidTest через GameUrls.appUrlOverride — MockWebServer.port).
            buildConfigField("String", "GAME_APP_URL", "\"http://127.0.0.1/app\"")
            buildConfigField("String", "GAME_LOGIN_URL", "\"http://127.0.0.1/login\"")
            buildConfigField("String", "GAME_HOST_MATCH", "\"127.0.0.1\"")
            // Инструментирование для JaCoCo: testInstrUnitTest и connectedInstrAndroidTest
            // пишут execution data, которую потом объединяет task jacocoCombinedReport.
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val hasSigningEnv = System.getenv("RELEASE_STORE") != null
            if (hasSigningEnv) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testBuildType = "instr"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // MockWebServer в unit-тестах для покрытия DefaultHttpFetcher и других
    // HTTP-потребителей без выхода в androidTest.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.contrib) {
        // espresso-contrib тянет material, который уже есть как implementation —
        // исключаем, чтобы избежать конфликта версий.
        exclude(group = "com.google.android.material", module = "material")
    }
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.espresso.web)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
}

// Объединённый JaCoCo-отчёт по веткам: execution data от testInstrUnitTest (JVM unit
// тесты) + connectedInstrAndroidTest (instrumented e2e на устройстве). Оба buildType
// instr с enableUnitTestCoverage/enableAndroidTestCoverage. Результат — HTML+XML
// в build/reports/jacoco/jacocoCombinedReport/.
//
// Исключения: автогенерированный Android код (R.*, BuildConfig, generated databinding),
// Kotlin-синтетика data-классов, DI-фабрики ViewModel (ViewModelProvider.Factory вызывает
// конструктор через reflection, JaCoCo видит синтетические ветки, недостижимые из тестов).
tasks.register<JacocoReport>("jacocoCombinedReport") {
    group = "verification"
    description = "Объединённый coverage по unit + androidTest для buildType instr"
    dependsOn("testInstrUnitTest", "connectedInstrAndroidTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    val fileFilter =
        listOf(
            "**/R.class",
            "**/R\$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            // Kotlin-синтетика, которую JaCoCo не может покрыть осмысленно
            "**/*\$Companion*.*",
            "**/*\$\$inlined*.*",
            "**/*\$WhenMappings*.*",
            // sealed class LauncherEvent — синтетика equals/hashCode/copy у дочерних
            // data-классов. Подклассы (ScriptAdded, ScriptDeleted, …) уже отправляются
            // через _events.send() и проверяются в observeViewModel, но JaCoCo считает
            // ветки сгенерированного компилятором equals/hashCode за непройденные.
            "**/launcher/LauncherEvent*.*",
        )

    val buildDirPath = layout.buildDirectory.asFile.get()
    // AGP 9 + built-in Kotlin compiler: class-файлы Kotlin лежат в
    // build/intermediates/built_in_kotlinc/instr/compileInstrKotlin/classes.
    // Старый путь `build/tmp/kotlin-classes/instr` — AGP ≤ 8.
    val kotlinClasses =
        fileTree("$buildDirPath/intermediates/built_in_kotlinc/instr/compileInstrKotlin/classes") {
            exclude(fileFilter)
        }
    val javaClasses =
        fileTree("$buildDirPath/intermediates/javac/instr/classes") {
            exclude(fileFilter)
        }

    classDirectories.setFrom(files(kotlinClasses, javaClasses))
    sourceDirectories.setFrom(files("$projectDir/src/main/java", "$projectDir/src/main/kotlin"))

    executionData.setFrom(
        fileTree(buildDirPath) {
            include(
                "outputs/unit_test_code_coverage/instrUnitTest/**/*.exec",
                "outputs/code_coverage/instrAndroidTest/connected/**/*.ec",
            )
        },
    )
}

// Копирует PNG-скриншоты из test storage в .github/images/screenshots/ для
// README. Запускается после connectedInstrAndroidTest с фильтром по аннотации
// @ReadmeScreenshot — см. .agents/skills/scout-screenshots/SKILL.md. AGP при
// useTestStorageService=true вытаскивает файлы, записанные через
// PlatformTestStorageRegistry, в build/outputs/connected_android_test_additional_output/
// (точная структура подкаталогов зависит от версии AGP, поэтому ищем walkTopDown).
val readmeScreenshotNames = setOf("game_loading.png", "game_settings.png", "settings.png", "script-manager.png")
val vrMaxChannelDiff = 8
val vrMaxDifferentFraction = 0.001

tasks.matching { it.name == "connectedInstrAndroidTest" }.configureEach {
    if (readmeScreenshotMode) {
        outputs.upToDateWhen { false }
        doFirst {
            val outputsDir =
                layout.buildDirectory
                    .dir("outputs/connected_android_test_additional_output")
                    .get()
                    .asFile
            if (outputsDir.exists()) {
                outputsDir
                    .walkTopDown()
                    .filter { it.isFile && it.parentFile.name == "readme" && it.extension == "png" }
                    .forEach { check(it.delete()) { "Не удалось удалить устаревший скриншот: $it" } }
            }
        }
    }
}

tasks.register("copyReadmeScreenshots") {
    group = "verification"
    description = "Скопировать README-скриншоты из test outputs в .github/images/screenshots/"
    doLast {
        val outputsDir =
            layout.buildDirectory
                .dir("outputs/connected_android_test_additional_output")
                .get()
                .asFile
        if (!outputsDir.exists()) {
            error(
                "Директория $outputsDir не существует. " +
                    "Сначала прогоните connectedInstrAndroidTest со скриншот-тестами " +
                    "(см. .agents/skills/scout-screenshots/SKILL.md).",
            )
        }
        val sourcesByName =
            outputsDir
                .walkTopDown()
                .filter { it.isFile && it.parentFile.name == "readme" && it.extension == "png" }
                .groupBy { it.name }
        check(sourcesByName.keys == readmeScreenshotNames && sourcesByName.values.all { it.size == 1 }) {
            "Ожидались ровно ${readmeScreenshotNames.sorted()}; найдены ${sourcesByName.mapValues { it.value.size }}"
        }
        val targetDir = rootProject.file(".github/images/screenshots")
        targetDir.mkdirs()
        for (name in readmeScreenshotNames.sorted()) {
            sourcesByName.getValue(name).single().copyTo(File(targetDir, name), overwrite = true)
        }
        logger.lifecycle("Скопировано в .github/images/screenshots/: ${readmeScreenshotNames.sorted().joinToString()}")
    }
}

// Одной CLI-командой обновить README-скриншоты: прогон @ReadmeScreenshot
// тестов на эмуляторе (фильтр по аннотации навешан на configuration phase в
// defaultConfig.testInstrumentationRunnerArguments при наличии этого task'а в
// startParameter) + копирование PNG в .github/images/screenshots/.
//
// Пользовательская обёртка над `connectedInstrAndroidTest -P...annotation=... && copyReadmeScreenshots`,
// без необходимости запоминать длинный CLI-флаг.
tasks.register("updateReadmeScreenshots") {
    group = "verification"
    description = "Прогнать @ReadmeScreenshot тесты + скопировать PNG в .github/images/screenshots/"
    dependsOn("connectedInstrAndroidTest")
    finalizedBy("copyReadmeScreenshots")
}

// Visual regression: тесты с @VisualRegressionTest пишут actual.png в test
// storage через VisualRegressionCapture. После прогона host-side задачи либо
// сравнивают actual с baseline в .github/images/visual-regression/ (в режиме
// проверки), либо перезаписывают baseline (в режиме обновления).
//
val vrBaselineDirRel = ".github/images/visual-regression"

tasks.register("checkReadmeLoadingSpinner") {
    group = "verification"
    description = "Проверить форму индикатора загрузки в README-скриншоте"
    doLast {
        validatePageLoadingSpinner(rootProject.file(".github/images/screenshots/game_loading.png"))
    }
}

tasks.register("compareVisualRegressionBaselines") {
    group = "verification"
    description = "Сравнить actual.png из VR-тестов с baseline в .github/images/visual-regression/"
    doLast {
        val outputsDir =
            layout.buildDirectory
                .dir("outputs/connected_android_test_additional_output")
                .get()
                .asFile
        if (!outputsDir.exists()) {
            error(
                "Директория $outputsDir не существует. " +
                    "Сначала прогоните connectedInstrAndroidTest с @VisualRegressionTest.",
            )
        }
        val baselineDir = rootProject.file(vrBaselineDirRel)
        val reportsDir =
            layout.buildDirectory
                .dir("reports/visual-regression")
                .get()
                .asFile
        reportsDir.mkdirs()

        val actuals =
            outputsDir
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".actual.png") }
                .toList()
        if (actuals.isEmpty()) {
            error("Не найдено ни одного *.actual.png в $outputsDir.")
        }

        val mismatches = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (actual in actuals) {
            val name = actual.name.removeSuffix(".actual.png")
            val baseline = File(baselineDir, "$name.png")
            if (!baseline.exists()) {
                missing += name
                continue
            }
            val diff = compareVisualRegressionImages(baseline, actual, File(reportsDir, "$name.diff.png"))
            if (diff.differentFraction > vrMaxDifferentFraction) {
                mismatches +=
                    "$name: ${"%.3f".format(diff.differentFraction * 100)}% pixels differ " +
                    "(threshold ${"%.3f".format(vrMaxDifferentFraction * 100)}%)"
                logger.lifecycle(
                    "Visual regression: $name → diff at ${File(reportsDir, "$name.diff.png").absolutePath}",
                )
            }
        }
        if (missing.isNotEmpty()) {
            error(
                "Не найдены baseline'ы: ${missing.sorted().joinToString()}. " +
                    "Запустите ./gradlew updateVisualRegressionBaselines.",
            )
        }
        if (mismatches.isNotEmpty()) {
            error(
                "Visual regression обнаружена в ${mismatches.size} тесте(ах):\n" +
                    mismatches.joinToString("\n") +
                    "\n\nDiff-картинки в $reportsDir.",
            )
        }
        logger.lifecycle("Все ${actuals.size} VR-baseline'а совпали (threshold ${vrMaxDifferentFraction * 100}%).")
    }
}

tasks.register("copyVisualRegressionBaselines") {
    group = "verification"
    description = "Скопировать actual.png в .github/images/visual-regression/<name>.png (overwrite)"
    doLast {
        val outputsDir =
            layout.buildDirectory
                .dir("outputs/connected_android_test_additional_output")
                .get()
                .asFile
        if (!outputsDir.exists()) {
            error(
                "Директория $outputsDir не существует. " +
                    "Сначала прогоните connectedInstrAndroidTest с @VisualRegressionTest.",
            )
        }
        val targetDir = rootProject.file(vrBaselineDirRel)
        targetDir.mkdirs()
        val copied = mutableListOf<String>()
        outputsDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".actual.png") }
            .forEach { source ->
                val name = source.name.removeSuffix(".actual.png")
                val target = File(targetDir, "$name.png")
                source.copyTo(target, overwrite = true)
                copied += "$name.png"
            }
        if (copied.isEmpty()) {
            error("Не найдено ни одного *.actual.png в $outputsDir.")
        }
        logger.lifecycle("Обновлено в $vrBaselineDirRel/: ${copied.sorted().joinToString()}")
    }
}

tasks.register("visualRegressionTest") {
    group = "verification"
    description = "Прогнать @VisualRegressionTest тесты и сравнить с baseline в .github/images/visual-regression/"
    dependsOn("connectedInstrAndroidTest")
    finalizedBy("compareVisualRegressionBaselines")
}

tasks.register("updateVisualRegressionBaselines") {
    group = "verification"
    description = "Перегенерировать baseline'ы visual regression в .github/images/visual-regression/"
    dependsOn("connectedInstrAndroidTest")
    finalizedBy("copyVisualRegressionBaselines")
}

// Превращает локальный snapshot реальной страницы игры (Save Page As Webpage Complete
// в refs/game/private/) в одиночную HTML-фикстуру для GameSettingsScreenshotE2ETest.
// Инлайнит CSS, выкидывает <script>-теги и внешние ссылки, добавляет stub-инициализацию
// (i18next.isInitialized=true, без экрана загрузки, localStorage.settings.lang='ru').
// Результат — app/src/androidTest/assets/fixtures/game-snapshot.html (gitignored).
//
// Если refs/game/private/ пуст или отсутствует — task удаляет game-snapshot.html (если был).
// Тест GameSettingsScreenshotE2ETest сам fallback-нет на mock-фикстуру
// app-page-with-settings-content-realistic.html.
tasks.register("inlineGameSnapshot") {
    group = "build"
    description =
        "Inline real-game snapshot из refs/game/private/ в androidTest assets как game-snapshot.html"

    val snapshotsDir = rootProject.file("refs/game/private")
    val outputFile = file("src/androidTest/assets/fixtures/game-snapshot.html")

    // inputs.files(fileTree(...)) тихо возвращает пустой набор, если директории
    // нет; в Gradle 9.3.1 inputs.dir(...).optional() не считает отсутствие
    // допустимым и валит configuration phase в CI, где refs/game/private/ нет
    // никогда (директория - локальный snapshot реальной игры, gitignored).
    inputs.files(fileTree(snapshotsDir)).withPropertyName("snapshotsFiles")
    outputs.file(outputFile)

    doLast {
        if (!snapshotsDir.exists() || snapshotsDir.listFiles().isNullOrEmpty()) {
            if (outputFile.exists()) outputFile.delete()
            logger.lifecycle("refs/game/private/ пуст, game-snapshot.html не создан")
            return@doLast
        }
        val htmlFile =
            snapshotsDir
                .listFiles { f -> f.isFile && f.name.endsWith(".html") }
                ?.firstOrNull()
        val filesDir =
            snapshotsDir
                .listFiles { f -> f.isDirectory && f.name.endsWith("_files") }
                ?.firstOrNull()
        if (htmlFile == null || filesDir == null) {
            if (outputFile.exists()) outputFile.delete()
            logger.warn(
                "В refs/game/private/ ожидаются *.html и *_files/ из 'Save Page As Webpage " +
                    "Complete'. Не найдено - game-snapshot.html не создан.",
            )
            return@doLast
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(preprocessGameSnapshot(htmlFile, filesDir), Charsets.UTF_8)
        logger.lifecycle("Создан game-snapshot.html (${outputFile.length()} байт)")
    }
}

// Assets и lint читают одну сгенерированную фикстуру. Все потребители должны
// дождаться snapshot, в том числе при совместном запуске lint и e2e.
val gameSnapshotConsumerTasks =
    setOf(
        "mergeInstrAndroidTestAssets",
        "generateInstrAndroidTestLintModel",
        "lintAnalyzeInstrAndroidTest",
    )
tasks.matching { it.name in gameSnapshotConsumerTasks }.configureEach {
    dependsOn("inlineGameSnapshot")
}

fun preprocessGameSnapshot(
    htmlFile: File,
    filesDir: File,
): String {
    var html = htmlFile.readText(Charsets.UTF_8)
    // Удалить все <script>...</script> (многострочные, ленивая семантика).
    html = html.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
    // Удалить ссылки на manifest/icons (они уйдут на sbg-game.ru, у нас сети нет).
    html =
        html.replace(
            Regex("(?i)<link\\s+rel=\"(?:manifest|shortcut icon|apple-touch-icon)\"[^>]*>"),
            "",
        )
    // Удалить yandex-metrika noscript-imgs.
    html = html.replace(Regex("(?is)<noscript>.*?</noscript>"), "")
    // Удалить <div id="map"> с canvas-слоями OpenLayers - на скриншоте нужны
    // только настройки игры в popup'е, а map канвасы при software-рендере
    // WebView могут перекрывать popup, давая всю страницу как белый прямоугольник.
    html = html.replace(Regex("(?is)<div id=\"map\">.*?</div>\\s*(?=<)"), "")
    // Раскрыть .settings popup: в DOM-snapshot он сохранён с классом 'hidden'
    // (popup закрывается JS на close, но в момент сохранения уже был открыт -
    // Chrome пишет последнее значение className, и `hidden` остаётся атавизмом).
    // Без JS у нас нет того кода, что снимает `hidden`, поэтому удаляем вручную.
    html =
        html.replace(
            Regex("""(?i)(<div\s+class="settings popup[^"]*?)\s+hidden(\s+[^"]*?|)"""),
        ) { m -> m.groupValues[1] + m.groupValues[2] }
    // Inline <link rel="stylesheet" href="./PATH/X.css">: подставить содержимое CSS.
    val cssLinkRegex =
        Regex(
            "(?i)<link\\s+(?:[^>]*?)href=\"\\./[^\"]*?/([^\"/]+\\.css)\"[^>]*>",
        )
    html =
        html.replace(cssLinkRegex) { match ->
            val cssName = match.groupValues[1]
            val cssFile = File(filesDir, cssName)
            if (cssFile.exists()) {
                "<style>\n${cssFile.readText(Charsets.UTF_8)}\n</style>"
            } else {
                "<!-- inlineGameSnapshot: missing $cssName -->"
            }
        }
    // Stub перед </head>: i18next готов, locale ru, ru-переводы для data-i18n
    // ключей (без сети i18next-http-backend не загружает их сам, default-тексты
    // в DOM остаются английскими). Перевод ключей, которые попадают в README-
    // скриншот секции настроек игры. Также подмена опции "System" в селекторе
    // языка - в snapshot её текст hardcoded без data-i18n.
    // Экран загрузки .loading-screen игра удаляет своим скриптом, когда загрузилась;
    // скрипты из snapshot вырезаны, поэтому, если snapshot сохранён с экраном
    // загрузки, stub удаляет его сам. Иначе ScoutBridge не сочтёт игру загруженной
    // и не вставит кнопку, которую снимает скриншот.
    val stub =
        """
        <script>
            window.i18next = { isInitialized: true };
            try { localStorage.setItem('settings', JSON.stringify({ lang: 'ru', theme: 'auto' })); } catch (e) {}
            window.__sbgFakeReady = true;
            window.__sbgFakePage = "app";
            (function() {
                var T = {
                    'menu.settings': 'Настройки',
                    'settings.global.header': 'Общее',
                    'settings.global.language': 'Язык',
                    'settings.global.theme': 'Тема',
                    'settings.global.theme-opts.auto': 'Авто',
                    'settings.global.theme-opts.light': 'Светлая',
                    'settings.global.theme-opts.dark': 'Тёмная',
                    'settings.interface.header': 'Интерфейс',
                    'settings.interface.imghid': 'Скрыть картинку',
                    'settings.interface.dsvhid': 'Скрыть результат поиска',
                    'settings.interface.arabic': 'Арабские цифры'
                };
                function apply() {
                    document.querySelectorAll('.loading-screen').forEach(function(element) {
                        element.remove();
                    });
                    document.querySelectorAll('[data-i18n]').forEach(function(el) {
                        var k = el.getAttribute('data-i18n');
                        if (T[k]) el.textContent = T[k];
                    });
                    var sysOpt = document.querySelector('select[data-setting="lang"] option[value="sys"]');
                    if (sysOpt) sysOpt.textContent = 'Системный';
                    // Выставить selected по нашей локали - в snapshot язык
                    // мог быть сохранён "sys", а на скриншоте мы хотим
                    // показать выбранный "Русский".
                    var langSel = document.querySelector('select[data-setting="lang"]');
                    if (langSel) langSel.value = 'ru';
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', apply);
                } else {
                    apply();
                }
            })();
        </script>
        """.trimIndent()
    html = html.replace(Regex("(?i)</head>"), "$stub\n</head>")
    return html
}

/**
 * Результат сравнения actual.png с baseline.png в visual regression.
 *
 * [differentFraction] - доля пикселей, где per-channel diff превышает
 * [vrMaxChannelDiff]. Превышение [vrMaxDifferentFraction] = регрессия.
 */
data class VisualRegressionDiff(
    val differentFraction: Double,
    val totalPixels: Int,
)

/**
 * Сравнивает [baseline] и [actual] PNG-файлы попиксельно с per-channel
 * tolerance ([vrMaxChannelDiff]) и записывает triplet diff-image
 * `[baseline | actual | highlight]` в [diffOutput] для review в CI-артефакте.
 *
 * Highlight-канал: пиксели, где diff превышает tolerance, окрашиваются в
 * solid magenta (#FF00FF) - быстро находить в triplet'е местоположение
 * регрессии. Идентичные пиксели остаются как в actual (для ориентации).
 *
 * Если размеры baseline и actual не совпадают - 100% diff, triplet строится
 * по max(width, height); это сразу очевидно ревьюеру.
 */
fun compareVisualRegressionImages(
    baseline: File,
    actual: File,
    diffOutput: File,
): VisualRegressionDiff {
    val baselineImage = ImageIO.read(baseline) ?: error("Не читается baseline: $baseline")
    val actualImage = ImageIO.read(actual) ?: error("Не читается actual: $actual")

    val baselineWidth = baselineImage.width
    val baselineHeight = baselineImage.height
    val actualWidth = actualImage.width
    val actualHeight = actualImage.height

    if (baselineWidth != actualWidth || baselineHeight != actualHeight) {
        // Размеры не совпадают - регрессия 100%. Triplet строится по max
        // размеру: ImageIO замостит недостающие области прозрачным фоном,
        // ревьюер сразу видит расхождение размеров.
        val tripletWidth = maxOf(baselineWidth, actualWidth)
        val tripletHeight = maxOf(baselineHeight, actualHeight)
        val triplet = BufferedImage(tripletWidth * 3, tripletHeight, BufferedImage.TYPE_INT_ARGB)
        val g = triplet.createGraphics()
        g.drawImage(baselineImage, 0, 0, null)
        g.drawImage(actualImage, tripletWidth, 0, null)
        // Highlight area при mismatch размеров - solid magenta на всю площадь
        // actual'а: визуально показывает, что весь PNG отличается.
        g.color = Color(0xFF, 0x00, 0xFF)
        g.fillRect(tripletWidth * 2, 0, actualWidth, actualHeight)
        g.dispose()
        ImageIO.write(triplet, "PNG", diffOutput)
        return VisualRegressionDiff(
            differentFraction = 1.0,
            totalPixels = tripletWidth * tripletHeight,
        )
    }

    val width = baselineWidth
    val height = baselineHeight
    val highlight = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    var differentPixels = 0
    val magenta = 0xFFFF00FF.toInt()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val b = baselineImage.getRGB(x, y)
            val a = actualImage.getRGB(x, y)
            if (vrPixelDiffers(b, a, vrMaxChannelDiff)) {
                differentPixels++
                highlight.setRGB(x, y, magenta)
            } else {
                highlight.setRGB(x, y, a)
            }
        }
    }

    val triplet = BufferedImage(width * 3, height, BufferedImage.TYPE_INT_ARGB)
    val g = triplet.createGraphics()
    g.drawImage(baselineImage, 0, 0, null)
    g.drawImage(actualImage, width, 0, null)
    g.drawImage(highlight, width * 2, 0, null)
    g.dispose()
    ImageIO.write(triplet, "PNG", diffOutput)

    return VisualRegressionDiff(
        differentFraction = differentPixels.toDouble() / (width * height),
        totalPixels = width * height,
    )
}

fun validatePageLoadingSpinner(file: File) {
    val image = ImageIO.read(file) ?: error("Не читается скриншот: $file")
    val pixelsPerDp = pixel4PixelsPerDp(image)
    val centerX = image.width - 32 * pixelsPerDp
    val centerY = image.height - 32 * pixelsPerDp
    val innerRadiusSquared = 7 * pixelsPerDp * 7 * pixelsPerDp
    val ringInnerRadiusSquared = 9 * pixelsPerDp * 9 * pixelsPerDp
    val ringOuterRadiusSquared = 12 * pixelsPerDp * 12 * pixelsPerDp
    var darkCenterPixels = 0
    var darkRingPixels = 0
    for (y in floor(centerY - 12 * pixelsPerDp).toInt()..ceil(centerY + 12 * pixelsPerDp).toInt()) {
        for (x in floor(centerX - 12 * pixelsPerDp).toInt()..ceil(centerX + 12 * pixelsPerDp).toInt()) {
            val distanceSquared = (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
            val pixel = image.getRGB(x, y)
            val dark =
                ((pixel ushr 16) and 0xFF) < 150 &&
                    ((pixel ushr 8) and 0xFF) < 150 &&
                    (pixel and 0xFF) < 150
            if (dark && distanceSquared <= innerRadiusSquared) darkCenterPixels++
            if (dark && distanceSquared in ringInnerRadiusSquared..ringOuterRadiusSquared) darkRingPixels++
        }
    }
    check(darkCenterPixels == 0 && darkRingPixels > 0) {
        "Индикатор загрузки на $file должен быть дугой с пустым центром: " +
            "$darkCenterPixels тёмных пикселей внутри, $darkRingPixels тёмных пикселей на дуге"
    }
    check(image.width >= 720) { "README-скриншот $file имеет ширину ${image.width}px; нужно не менее 720px" }
}

fun pixel4PixelsPerDp(image: BufferedImage): Double = image.width * 440.0 / (1080 * 160)

private fun vrPixelDiffers(
    p1: Int,
    p2: Int,
    maxChannelDiff: Int,
): Boolean {
    if (p1 == p2) return false
    val r1 = (p1 ushr 16) and 0xFF
    val r2 = (p2 ushr 16) and 0xFF
    if (abs(r1 - r2) > maxChannelDiff) return true
    val g1 = (p1 ushr 8) and 0xFF
    val g2 = (p2 ushr 8) and 0xFF
    if (abs(g1 - g2) > maxChannelDiff) return true
    val b1 = p1 and 0xFF
    val b2 = p2 and 0xFF
    if (abs(b1 - b2) > maxChannelDiff) return true
    return false
}
