package com.github.wrager.sbgscout.e2e.screenshots

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
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
        GameScreen(scenario, idling).waitForLoaded().openSettings()

        // BuildConfig.VERSION_NAME у instr-варианта содержит суффикс "-instr"
        // (см. app/build.gradle.kts versionNameSuffix). На скриншоте README хотим
        // версию в том виде, в каком её увидит конечный пользователь на release-APK
        // (без суффикса) - поэтому подменяем summary preference в тесте.
        // Trim делаем по фактическому значению, чтобы при смене суффикса в gradle
        // (debug/instr/release) тест не приходилось править.
        var recyclerView: RecyclerView? = null
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer) as PreferenceFragmentCompat
            val versionPref = fragment.findPreference<Preference>(
                SettingsOverlayScreen.KEY_APP_VERSION,
            ) ?: error("Preference '${SettingsOverlayScreen.KEY_APP_VERSION}' не найден")
            val raw = versionPref.summary?.toString().orEmpty()
            versionPref.summary = raw.substringBefore('-')
            // E2ETestBase в setUpE2E ставит auto_check_updates=false, чтобы тесты
            // не ходили в GitHub за релизами на старте Activity. Для скриншота
            // README показываем prod-default (defaultValue="true" в preferences.xml) -
            // переключаем switch обратно на on, не задевая prod-код.
            val autoCheckPref = fragment.findPreference<SwitchPreferenceCompat>(
                SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES,
            ) ?: error("Preference '${SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES}' не найден")
            autoCheckPref.isChecked = true
            recyclerView = fragment.listView
        }
        ReadmeScreenshotCapture.captureFullScreenWithScroll(
            "settings",
            recyclerView ?: error("PreferenceFragmentCompat.listView не найден"),
        )
    }
}
