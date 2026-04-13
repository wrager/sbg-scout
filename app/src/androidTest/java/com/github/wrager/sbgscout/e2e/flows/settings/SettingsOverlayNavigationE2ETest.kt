package com.github.wrager.sbgscout.e2e.flows.settings

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.launcher.ScriptListFragment
import com.github.wrager.sbgscout.settings.SettingsFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Навигация внутри settings overlay в [GameActivity]:
 * 1) клик по `closeSettingsButton` закрывает overlay;
 * 2) system back на `SettingsFragment` overlay — намеренный no-op
 *    (GameActivity.setupBackPressHandling игнорирует back пока `isSettingsOpen`;
 *    закрытие только через плавающую кнопку [x]);
 * 3) system back на embedded `ScriptListFragment` — такой же намеренный no-op
 *    (GameActivity callback регистрируется ПОСЛЕ FragmentManager callback,
 *    поэтому срабатывает первым и поглощает back до FragmentManager).
 *
 * Пункты (2) и (3) фиксируют намеренный инвариант: любой код, который случайно
 * изменит порядок регистрации callback'ов или уберёт `if (isSettingsOpen()) return`,
 * упадёт в этих тестах и заставит осознанно решить — хотим ли такое изменение UX.
 */
class SettingsOverlayNavigationE2ETest : E2ETestBase() {

    @Test
    fun closeButton_click_hidesOverlayAndShowsTopButtons() {
        val scenario = setupFakeGameAndLaunch()
        GameScreen(scenario, idling).waitForLoaded().openSettings()
        assertOverlayVisible(scenario)

        clickCloseButton(scenario)

        assertOverlayHidden(scenario)
    }

    @Test
    fun systemBack_onSettingsFragment_isNoOp_overlayStaysOpen() {
        val scenario = setupFakeGameAndLaunch()
        GameScreen(scenario, idling).waitForLoaded().openSettings()
        assertOverlayVisible(scenario)
        assertSettingsFragmentShown(scenario)

        Espresso.pressBack()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertOverlayVisible(scenario)
        assertSettingsFragmentShown(scenario)
    }

    @Test
    fun systemBack_onEmbeddedScriptListFragment_isNoOp_fragmentStaysOpen() {
        val scenario = setupFakeGameAndLaunch()
        GameScreen(scenario, idling).waitForLoaded()
            .openSettings()
            .openManageScripts()
        assertOverlayVisible(scenario)
        assertScriptListFragmentShown(scenario)

        Espresso.pressBack()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Overlay и ScriptListFragment всё ещё на месте: GameActivity-callback
        // первым поглотил back (LIFO в OnBackPressedDispatcher — он зарегистрирован
        // ПОСЛЕ FragmentManager callback в onCreate), и ничего не произошло.
        assertOverlayVisible(scenario)
        assertScriptListFragmentShown(scenario)
    }

    private fun setupFakeGameAndLaunch(): ActivityScenario<GameActivity> {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        return launchGameActivity()
    }

    private fun clickCloseButton(scenario: ActivityScenario<GameActivity>) {
        scenario.onActivity { activity ->
            val closeButton = activity.findViewById<View>(R.id.closeSettingsButton)
                ?: error("closeSettingsButton не найден в GameActivity")
            closeButton.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertOverlayVisible(scenario: ActivityScenario<GameActivity>) {
        scenario.onActivity { activity ->
            val container = activity.findViewById<View>(R.id.settingsContainer)
            val topButtons = activity.findViewById<View>(R.id.topButtonsContainer)
            assertEquals(
                "settingsContainer должен быть VISIBLE",
                View.VISIBLE,
                container.visibility,
            )
            // topButtonsContainer скрывается при открытии overlay.
            assertEquals(
                "topButtonsContainer должен быть GONE при открытом overlay",
                View.GONE,
                topButtons.visibility,
            )
        }
    }

    private fun assertOverlayHidden(scenario: ActivityScenario<GameActivity>) {
        scenario.onActivity { activity ->
            val container = activity.findViewById<View>(R.id.settingsContainer)
            val topButtons = activity.findViewById<View>(R.id.topButtonsContainer)
            assertEquals(
                "settingsContainer должен быть GONE после закрытия",
                View.GONE,
                container.visibility,
            )
            assertEquals(
                "topButtonsContainer должен быть VISIBLE после закрытия overlay",
                View.VISIBLE,
                topButtons.visibility,
            )
        }
    }

    private fun assertSettingsFragmentShown(scenario: ActivityScenario<GameActivity>) {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
            assertTrue(
                "В settingsContainer должен быть SettingsFragment, а не ${fragment?.javaClass?.simpleName}",
                fragment is SettingsFragment,
            )
        }
    }

    private fun assertScriptListFragmentShown(scenario: ActivityScenario<GameActivity>) {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
            assertTrue(
                "В settingsContainer должен быть ScriptListFragment, а не ${fragment?.javaClass?.simpleName}",
                fragment is ScriptListFragment,
            )
        }
    }
}
