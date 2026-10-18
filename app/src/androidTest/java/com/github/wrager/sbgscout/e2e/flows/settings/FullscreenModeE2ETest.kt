package com.github.wrager.sbgscout.e2e.flows.settings

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет вторую сторону контракта fullscreen: изменение `fullscreen_mode`
 * в `SharedPreferences` вызывает `applyFullscreen` в `GameActivity` через
 * `OnSharedPreferenceChangeListener`, и реальное состояние окна меняется.
 *
 * Первая сторона контракта (клик в SettingsFragment → запись в prefs)
 * покрыта в SettingsScreenE2ETest. Здесь мы эмулируем то, что сделает
 * SettingsFragment — программно меняем prefs, пока GameActivity запущена.
 */
@RunWith(AndroidJUnit4::class)
class FullscreenModeE2ETest : E2ETestBase() {
    @Test
    fun changingFullscreenPref_togglesGameActivityWindowState() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        // E2ETestBase в disableAutoUpdateCheck уже трогает этот же prefs file.
        // Начинаем с выключенного fullscreen.
        prefs.edit().putBoolean(KEY_FULLSCREEN, false).commit()

        val scenario = launchGameActivity()
        GameScreen(scenario, idling).waitForLoaded()

        scenario.onActivity { activity ->
            assertFalse(
                "При старте с fullscreen_mode=false GameActivity.isFullscreen должен быть false",
                activity.isFullscreen,
            )
        }

        // Эмулируем действие SettingsFragment: запись в prefs должна
        // асинхронно триггерить OnSharedPreferenceChangeListener в GameActivity.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putBoolean(KEY_FULLSCREEN, true).commit()
        }

        scenario.onActivity { activity ->
            assertTrue(
                "После включения fullscreen_mode GameActivity.isFullscreen должен стать true",
                activity.isFullscreen,
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putBoolean(KEY_FULLSCREEN, false).commit()
        }

        scenario.onActivity { activity ->
            assertFalse(
                "После выключения fullscreen_mode GameActivity.isFullscreen должен вернуться в false",
                activity.isFullscreen,
            )
        }
    }

    private companion object {
        const val KEY_FULLSCREEN = "fullscreen_mode"
    }
}
