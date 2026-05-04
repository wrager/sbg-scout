package com.github.wrager.sbgscout.e2e.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает settings.png — overlay настроек приложения (SettingsFragment поверх
 * GameActivity). Через [ReadmeScreenshotCapture.captureFullScreenWithScroll] —
 * длинный stitched-PNG с status bar, footer и прокручиваемым списком preferences.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsScreenshotE2ETest : E2ETestBase() {

    @Before
    fun setUpLocale() {
        ReadmeScreenshotCapture.forceRussianLocale()
    }

    @After
    fun tearDownLocale() {
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @ReadmeScreenshot
    fun captureSettingsScreenshot() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val settings = GameScreen(scenario, idling).waitForLoaded().openSettings()

        // BuildConfig.VERSION_NAME у instr-варианта содержит суффикс "-instr"
        // (см. app/build.gradle.kts versionNameSuffix). На скриншоте README хотим
        // версию в том виде, в каком её увидит конечный пользователь release-APK
        // (без суффикса).
        settings.stripBuildTypeSuffixFromAppVersion()
        // E2ETestBase в setUpE2E ставит auto_check_updates=false, чтобы тесты
        // не ходили в GitHub за релизами на старте Activity. Для скриншота
        // README показываем prod-default (defaultValue="true" в preferences.xml) -
        // переключаем switch обратно на on, не задевая prod-код.
        settings.setSwitchPreferenceChecked(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES, true)

        ReadmeScreenshotCapture.captureFullScreenWithScroll(
            "settings",
            settings.preferencesRecyclerView(),
        )
    }
}
