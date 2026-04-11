package com.github.wrager.sbgscout.e2e.flows.settings

import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полное e2e-покрытие экрана настроек как overlay внутри GameActivity.
 *
 * Взаимодействия с preferences идут через Preference API
 * ([SettingsOverlayScreen.clickPreferenceByKey]), а не через Espresso
 * onView/onData — это обходит проблемы lazy-binding RecyclerView в
 * PreferenceFragmentCompat и не зависит от отключения анимаций на эмуляторе.
 */
class SettingsOverlayE2ETest : E2ETestBase() {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val defaultPrefs get() =
        PreferenceManager.getDefaultSharedPreferences(targetContext)

    @Test
    fun overlay_showsAllFivePreferenceCategories() {
        setupFakeGameAndLaunch().openSettings().assertCategoryTitles(
            R.string.settings_category_display,
            R.string.settings_category_scripts,
            R.string.settings_category_game,
            R.string.settings_category_updates,
            R.string.settings_category_about,
        )
    }

    @Test
    fun togglesFullscreenPreference_writesBothDirectionsToPrefs() {
        defaultPrefs.edit().putBoolean(SettingsOverlayScreen.KEY_FULLSCREEN, false).commit()
        val overlay = setupFakeGameAndLaunch().openSettings()

        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_FULLSCREEN)
        assertTrue(
            "После клика fullscreen_mode должен стать true",
            defaultPrefs.getBoolean(SettingsOverlayScreen.KEY_FULLSCREEN, false),
        )

        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_FULLSCREEN)
        assertFalse(
            "После второго клика fullscreen_mode должен стать false",
            defaultPrefs.getBoolean(SettingsOverlayScreen.KEY_FULLSCREEN, true),
        )
    }

    @Test
    fun togglesKeepScreenOnPreference_writesBothDirectionsToPrefs() {
        defaultPrefs.edit().putBoolean(SettingsOverlayScreen.KEY_KEEP_SCREEN_ON, false).commit()
        val overlay = setupFakeGameAndLaunch().openSettings()

        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_KEEP_SCREEN_ON)
        assertTrue(defaultPrefs.getBoolean(SettingsOverlayScreen.KEY_KEEP_SCREEN_ON, false))

        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_KEEP_SCREEN_ON)
        assertFalse(defaultPrefs.getBoolean(SettingsOverlayScreen.KEY_KEEP_SCREEN_ON, true))
    }

    @Test
    fun togglesAutoCheckUpdatesPreference_writesBothDirectionsToPrefs() {
        defaultPrefs.edit().putBoolean(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES, true).commit()
        val overlay = setupFakeGameAndLaunch().openSettings()

        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES)
        assertFalse(defaultPrefs.getBoolean(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES, true))

        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES)
        assertTrue(defaultPrefs.getBoolean(SettingsOverlayScreen.KEY_AUTO_CHECK_UPDATES, false))
    }

    @Test
    fun appVersionPreference_displaysBuildConfigVersionNameInSummary() {
        setupFakeGameAndLaunch().openSettings().assertPreferenceSummaryContains(
            key = SettingsOverlayScreen.KEY_APP_VERSION,
            substring = BuildConfig.VERSION_NAME,
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
        val game = setupFakeGameAndLaunch()
        // Дожидаемся первого запроса /app (вырезается из очереди).
        server.takeRequestMatching { it.path == "/app" && it.method == "GET" }

        game.openSettings().clickPreferenceByKey(SettingsOverlayScreen.KEY_RELOAD_GAME)

        // После клика GameActivity делает closeSettings + webView.loadUrl(GameUrls.appUrl).
        // Значит fake-сервер должен получить второй GET /app.
        val second = server.takeRequestMatching(5_000L) {
            it.path == "/app" && it.method == "GET"
        }
        assertEquals("/app", second.path)
    }

    private fun setupFakeGameAndLaunch(): GameScreen {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val scenario = launchGameActivity()
        return GameScreen(scenario, idling).waitForLoaded()
    }
}
