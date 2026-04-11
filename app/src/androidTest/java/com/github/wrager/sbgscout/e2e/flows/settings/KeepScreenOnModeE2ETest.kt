package com.github.wrager.sbgscout.e2e.flows.settings

import android.view.WindowManager
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Вторая сторона контракта `keep_screen_on`: изменение prefs должно
 * через `OnSharedPreferenceChangeListener` вызывать `applyKeepScreenOn`
 * в GameActivity, что выставляет или снимает `FLAG_KEEP_SCREEN_ON` на окне.
 *
 * Первая сторона (запись prefs из SettingsFragment) покрывается в
 * SettingsScreenE2ETest — она не нужна GameActivity.
 */
@RunWith(AndroidJUnit4::class)
class KeepScreenOnModeE2ETest : E2ETestBase() {

    @Test
    fun changingKeepScreenOnPref_togglesWindowFlag() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        // GameActivity читает prefs в onCreate, дефолт = true. Явно выставляем false,
        // чтобы начать с известного состояния.
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, false).commit()

        val scenario = launchGameActivity()
        GameScreen(scenario, idling).waitForLoaded()

        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertEquals(
                "При старте с keep_screen_on=false флаг FLAG_KEEP_SCREEN_ON должен быть снят",
                0,
                flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, true).commit()
        }

        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertEquals(
                "После включения keep_screen_on окно должно получить FLAG_KEEP_SCREEN_ON",
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, false).commit()
        }

        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertEquals(
                "После выключения keep_screen_on флаг должен быть снят",
                0,
                flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private companion object {
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
