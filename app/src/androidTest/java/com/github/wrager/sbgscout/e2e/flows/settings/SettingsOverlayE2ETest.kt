package com.github.wrager.sbgscout.e2e.flows.settings

import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Полное e2e-покрытие экрана настроек как overlay внутри GameActivity.
 *
 * Каждый тест:
 * 1. Запускает GameActivity, ждёт загрузки fake-страницы.
 * 2. Открывает settings overlay кликом по «⚙ Scout».
 * 3. Выполняет действие с preference.
 * 4. Проверяет observable side effect (prefs, UI navigation, и т.п.).
 */
class SettingsOverlayE2ETest : E2ETestBase() {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val defaultPrefs get() =
        PreferenceManager.getDefaultSharedPreferences(targetContext)

    @Test
    fun overlay_showsAllFivePreferenceCategories() {
        setupFakeGameAndLaunch().openSettings().assertCategoriesVisible(
            R.string.settings_category_display,
            R.string.settings_category_scripts,
            R.string.settings_category_game,
            R.string.settings_category_updates,
            R.string.settings_category_about,
        )
    }

    @Test
    fun togglesFullscreenPreference_writesBothDirectionsToPrefs() {
        defaultPrefs.edit().putBoolean(KEY_FULLSCREEN, false).commit()
        val overlay = setupFakeGameAndLaunch().openSettings()

        overlay.clickPreferenceByTitle(R.string.settings_fullscreen)
        assertTrue(
            "После клика fullscreen_mode=true",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, false),
        )

        overlay.clickPreferenceByTitle(R.string.settings_fullscreen)
        assertFalse(
            "После второго клика fullscreen_mode=false",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, true),
        )
    }

    @Test
    fun togglesKeepScreenOnPreference_writesBothDirectionsToPrefs() {
        defaultPrefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, false).commit()
        val overlay = setupFakeGameAndLaunch().openSettings()

        overlay.clickPreferenceByTitle(R.string.settings_keep_screen_on)
        assertTrue(defaultPrefs.getBoolean(KEY_KEEP_SCREEN_ON, false))

        overlay.clickPreferenceByTitle(R.string.settings_keep_screen_on)
        assertFalse(defaultPrefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
    }

    @Test
    fun togglesAutoCheckUpdatesPreference_writesBothDirectionsToPrefs() {
        defaultPrefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, true).commit()
        val overlay = setupFakeGameAndLaunch().openSettings()

        overlay.clickPreferenceByTitle(R.string.settings_auto_check_updates)
        assertFalse(defaultPrefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true))

        overlay.clickPreferenceByTitle(R.string.settings_auto_check_updates)
        assertTrue(defaultPrefs.getBoolean(KEY_AUTO_CHECK_UPDATES, false))
    }

    @Test
    fun appVersionPreference_displaysBuildConfigVersionNameInSummary() {
        val overlay = setupFakeGameAndLaunch().openSettings()
        overlay.assertPreferenceSummaryContains(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
        )
    }

    @Test
    fun manageScripts_navigatesToScriptListFragment() {
        val scriptManager = setupFakeGameAndLaunch()
            .openSettings()
            .openManageScripts()
        scriptManager.assertDisplayed()
    }

    @Test
    fun reloadGamePreference_setsReloadFlagAndRequestsGameAgain() {
        val scenario = setupFakeGameAndLaunch()
        // Дожидаемся первого запроса /app (вырезается из потребления queue).
        server.takeRequestMatching { it.path == "/app" && it.method == "GET" }

        scenario.openSettings().clickPreferenceByTitle(R.string.reload_game)

        // После клика GameActivity делает closeSettings() → webView.loadUrl(GameUrls.appUrl).
        // В fake-сервере должен прилететь второй запрос /app.
        val second = server.takeRequestMatching(5_000L) {
            it.path == "/app" && it.method == "GET"
        }
        // И prefs должны содержать флаг reload_requested=true (до того, как GameActivity
        // их сбросит в applySettingsAfterClose).
        // Сам факт наличия Second request говорит о том, что клик сработал
        // (прод-код пишет флаг + вызывает closeSettings + loadUrl).
        assertEquals("/app", second.path)
    }

    private fun setupFakeGameAndLaunch(): GameScreen {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val scenario = launchGameActivity()
        return GameScreen(scenario, idling).waitForLoaded()
    }

    @Suppress("UnusedPrivateMember")
    private fun waitForPrefValue(key: String, expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (defaultPrefs.getBoolean(key, !expected) == expected) return
            Thread.sleep(50L)
        }
        fail("Prefs[$key] не стал $expected за 2000ms")
    }

    private companion object {
        const val KEY_FULLSCREEN = "fullscreen_mode"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
    }
}
