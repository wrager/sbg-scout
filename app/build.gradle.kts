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
        val versionMajor = 0
        val versionMinor = 15
        val versionPatch = 4
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
