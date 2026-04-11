import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.github.wrager.sbgscout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.wrager.sbgscout"
        minSdk = 24
        targetSdk = 35
        val versionMajor = 0
        val versionMinor = 15
        val versionPatch = 4
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

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
}
