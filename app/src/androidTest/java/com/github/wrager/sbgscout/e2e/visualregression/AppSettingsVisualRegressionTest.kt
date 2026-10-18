package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает settings overlay (SettingsFragment поверх GameActivity) в обеих
 * темах: app-settings-light.png и app-settings-dark.png.
 *
 * Параллель `AppSettingsScreenshotE2ETest`, но с native-разрешением (без
 * масштабирования до 360px) и зафиксированной темой через [ThemeOverride].
 * Локаль - русская: VR-baseline должен быть устойчив к системной локали
 * эмулятора, иначе тесты на CI с другой системной локалью покажут diff.
 *
 * Тема выставляется в теле каждого теста, не в `@Before`: один тест-класс
 * содержит оба варианта (light, dark), у них разные нужные `nightMode`.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsVisualRegressionTest : E2ETestBase() {
    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
        StatusBarDemoMode.enable()
    }

    @After
    fun tearDownVisualRegression() {
        StatusBarDemoMode.disable()
        ThemeOverride.resetTheme()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @VisualRegressionTest
    fun captureAppSettingsLight() {
        ThemeOverride.forceLightTheme()
        captureSettings("app-settings-light")
    }

    @Test
    @VisualRegressionTest
    fun captureAppSettingsDark() {
        ThemeOverride.forceDarkTheme()
        captureSettings("app-settings-dark")
    }

    private fun captureSettings(baselineName: String) {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val settings = GameScreen(scenario, idling).waitForLoaded().openSettings()

        // BuildConfig.VERSION_NAME у instr-варианта содержит суффикс "-instr".
        // Чтобы baseline отражал prod-версию, обрезаем суффикс.
        settings.stripBuildTypeSuffixFromAppVersion()
        // E2ETestBase ставит auto_check_updates=false для подавления GitHub-чека
        // на старте. Для baseline показываем prod-default (true).
        settings.setSwitchPreferenceChecked(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES, true)

        VisualRegressionCapture.captureScrollableNative(
            baselineName,
            settings.preferencesRecyclerView(),
        )
    }
}
