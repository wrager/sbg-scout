import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

android {
    namespace = "com.github.wrager.sbgscout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.wrager.sbgscout"
        minSdk = 26
        targetSdk = 35
        val versionMajor = 1
        val versionMinor = 2
        val versionPatch = 0
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
// @ReadmeScreenshot — см. .claude/commands/screenshots.md. AGP при
// useTestStorageService=true вытаскивает файлы, записанные через
// PlatformTestStorageRegistry, в build/outputs/connected_android_test_additional_output/
// (точная структура подкаталогов зависит от версии AGP, поэтому ищем walkTopDown).
tasks.register("copyReadmeScreenshots") {
    group = "verification"
    description = "Скопировать README-скриншоты из test outputs в .github/images/screenshots/"
    doLast {
        val outputsDir = layout.buildDirectory.dir("outputs/connected_android_test_additional_output").get().asFile
        if (!outputsDir.exists()) {
            error(
                "Директория $outputsDir не существует. " +
                    "Сначала прогоните connectedInstrAndroidTest со скриншот-тестами " +
                    "(см. .claude/commands/screenshots.md).",
            )
        }
        val targetDir = rootProject.file(".github/images/screenshots")
        targetDir.mkdirs()
        val expectedNames = setOf("game_settings.png", "settings.png", "script-manager.png")
        val copied = mutableListOf<String>()
        outputsDir.walkTopDown()
            .filter { it.isFile && it.name in expectedNames }
            .forEach { source ->
                val target = File(targetDir, source.name)
                source.copyTo(target, overwrite = true)
                copied += source.name
            }
        if (copied.isEmpty()) {
            error(
                "В $outputsDir не найдено ни одного из ${expectedNames.joinToString()}. " +
                    "Проверьте, что скриншот-тесты с @ReadmeScreenshot прошли успешно.",
            )
        }
        logger.lifecycle("Скопировано в .github/images/screenshots/: ${copied.sorted().joinToString()}")
        val missing = expectedNames - copied.toSet()
        if (missing.isNotEmpty()) {
            logger.warn("Не найдены: ${missing.joinToString()}. Соответствующие тесты упали или не запускались.")
        }
    }
}

// Превращает локальный snapshot реальной страницы игры (Save Page As Webpage Complete
// в refs/game/private/) в одиночную HTML-фикстуру для GameSettingsScreenshotE2ETest.
// Инлайнит CSS, выкидывает <script>-теги и внешние ссылки, добавляет stub-инициализацию
// (i18next.isInitialized=true, localStorage.settings.lang='ru'). Результат —
// app/src/androidTest/assets/fixtures/game-snapshot.html (gitignored).
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

    inputs.dir(snapshotsDir).optional().withPropertyName("snapshotsDir")
    outputs.file(outputFile)

    doLast {
        if (!snapshotsDir.exists() || snapshotsDir.listFiles().isNullOrEmpty()) {
            if (outputFile.exists()) outputFile.delete()
            logger.lifecycle("refs/game/private/ пуст, game-snapshot.html не создан")
            return@doLast
        }
        val htmlFile =
            snapshotsDir.listFiles { f -> f.isFile && f.name.endsWith(".html") }
                ?.firstOrNull()
        val filesDir =
            snapshotsDir.listFiles { f -> f.isDirectory && f.name.endsWith("_files") }
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

// Форсим зависимость сборки androidTest assets от inlineGameSnapshot, чтобы изменения
// в refs/game/private/ автоматически прокатывались в фикстуры перед каждым тестовым прогоном.
afterEvaluate {
    tasks.findByName("mergeInstrAndroidTestAssets")?.dependsOn("inlineGameSnapshot")
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
    // Stub перед </head>: i18next готов, locale ru.
    val stub =
        """
        <script>
            window.i18next = { isInitialized: true };
            try { localStorage.setItem('settings', JSON.stringify({ lang: 'ru', theme: 'auto' })); } catch (e) {}
            window.__sbgFakeReady = true;
            window.__sbgFakePage = "app";
        </script>
        """.trimIndent()
    html = html.replace(Regex("(?i)</head>"), "$stub\n</head>")
    return html
}
