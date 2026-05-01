package com.github.wrager.sbgscout.e2e.flows.settings

import android.content.pm.ActivityInfo
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
 * Вторая сторона контракта `lock_portrait_orientation`: изменение prefs должно
 * через `OnSharedPreferenceChangeListener` вызывать `applyLockPortraitOrientation`
 * в GameActivity, что выставляет `requestedOrientation` в PORTRAIT или UNSPECIFIED.
 *
 * Первая сторона (запись prefs из SettingsFragment) покрывается в SettingsOverlayE2ETest.
 */
@RunWith(AndroidJUnit4::class)
class LockPortraitOrientationModeE2ETest : E2ETestBase() {

    @Test
    fun changingLockPortraitOrientationPref_togglesRequestedOrientation() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        // GameActivity читает prefs в onCreate, дефолт = true. Явно выставляем false,
        // чтобы начать с известного состояния.
        prefs.edit().putBoolean(KEY_LOCK_PORTRAIT_ORIENTATION, false).commit()

        val scenario = launchGameActivity()
        GameScreen(scenario, idling).waitForLoaded()

        scenario.onActivity { activity ->
            assertEquals(
                "При старте с lock_portrait_orientation=false requestedOrientation = UNSPECIFIED",
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                activity.requestedOrientation,
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putBoolean(KEY_LOCK_PORTRAIT_ORIENTATION, true).commit()
        }

        scenario.onActivity { activity ->
            assertEquals(
                "После включения lock_portrait_orientation requestedOrientation = PORTRAIT",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                activity.requestedOrientation,
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putBoolean(KEY_LOCK_PORTRAIT_ORIENTATION, false).commit()
        }

        scenario.onActivity { activity ->
            assertEquals(
                "После выключения lock_portrait_orientation requestedOrientation = UNSPECIFIED",
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                activity.requestedOrientation,
            )
        }
    }

    private companion object {
        const val KEY_LOCK_PORTRAIT_ORIENTATION = "lock_portrait_orientation"
    }
}
