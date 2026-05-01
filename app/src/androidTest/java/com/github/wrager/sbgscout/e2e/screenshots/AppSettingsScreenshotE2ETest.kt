package com.github.wrager.sbgscout.e2e.screenshots

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает settings.png — overlay настроек приложения (SettingsFragment поверх
 * GameActivity). Через [ReadmeScreenshotCapture.captureViewFullContent] —
 * полный контент включая прокручиваемые за viewport preferences.
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

        var fragmentView: View? = null
        scenario.onActivity { activity ->
            fragmentView = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
                ?.requireView()
        }
        ReadmeScreenshotCapture.captureViewFullContent(
            "settings",
            fragmentView ?: error("SettingsFragment.view не найден"),
        )
    }
}
