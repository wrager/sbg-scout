package com.github.wrager.sbgscout.e2e.flows.settings

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.SbgScoutApplication
import com.github.wrager.sbgscout.config.GameUrls
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2e-покрытие скрытого переключения бета-сервера:
 * - x5 тапов на "Версия" -> диалог подтверждения
 * - подтверждение -> флаг сохраняется в prefs, страница перезагружается
 * - отмена -> состояние не меняется
 * - подтверждение при активном бете -> флаг снимается
 *
 * Хост фактически не меняется (GameUrls.appUrlOverride задан инфраструктурой E2ETestBase),
 * но перезагрузка фиксируется через второй GET /app на fake-сервере.
 */
@RunWith(AndroidJUnit4::class)
class BetaServerToggleE2ETest : E2ETestBase() {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val defaultPrefs get() =
        PreferenceManager.getDefaultSharedPreferences(targetContext)

    @Test
    fun tapVersionFiveTimes_showsBetaToggleDialog() {
        val overlay = setupFakeGameAndLaunch().openSettings()
        overlay.tapVersionNTimes(SettingsOverlayScreen.BETA_TOGGLE_TAP_COUNT)
        overlay.assertBetaDialogDisplayed()
    }

    @Test
    fun betaToggle_confirmEnableBeta_setsFlagAndReloadsGame() {
        val game = setupFakeGameAndLaunch()
        server.takeRequestMatching { it.path == "/app" && it.method == "GET" }

        val overlay = game.openSettings()
        overlay.tapVersionNTimes(SettingsOverlayScreen.BETA_TOGGLE_TAP_COUNT)
        overlay.confirmBetaToggle()

        assertTrue(GameUrls.betaServerEnabled)
        assertTrue(defaultPrefs.getBoolean(SbgScoutApplication.KEY_BETA_SERVER_ENABLED, false))
        server.takeRequestMatching(5_000L) { it.path == "/app" && it.method == "GET" }
    }

    @Test
    fun betaToggle_cancelLeavesStateUnchanged() {
        val overlay = setupFakeGameAndLaunch().openSettings()
        overlay.tapVersionNTimes(SettingsOverlayScreen.BETA_TOGGLE_TAP_COUNT)
        overlay.cancelBetaToggle()

        assertFalse(GameUrls.betaServerEnabled)
        assertFalse(defaultPrefs.getBoolean(SbgScoutApplication.KEY_BETA_SERVER_ENABLED, false))
        overlay.assertDisplayed()
    }

    @Test
    fun betaToggle_confirmDisableBeta_clearsFlagAndReloadsGame() {
        GameUrls.betaServerEnabled = true
        defaultPrefs.edit()
            .putBoolean(SbgScoutApplication.KEY_BETA_SERVER_ENABLED, true)
            .commit()

        val game = setupFakeGameAndLaunch()
        server.takeRequestMatching { it.path == "/app" && it.method == "GET" }

        val overlay = game.openSettings()
        overlay.tapVersionNTimes(SettingsOverlayScreen.BETA_TOGGLE_TAP_COUNT)
        overlay.confirmBetaToggle()

        assertFalse(GameUrls.betaServerEnabled)
        assertFalse(defaultPrefs.getBoolean(SbgScoutApplication.KEY_BETA_SERVER_ENABLED, true))
        server.takeRequestMatching(5_000L) { it.path == "/app" && it.method == "GET" }
    }

    private fun setupFakeGameAndLaunch(): GameScreen {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        return GameScreen(launchGameActivity(), idling).waitForLoaded()
    }
}
