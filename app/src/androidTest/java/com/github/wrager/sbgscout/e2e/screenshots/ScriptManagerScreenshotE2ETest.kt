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
 * Снимает script-manager.png — список скриптов (ScriptListFragment) внутри
 * settingsContainer. Bundled SVP-скрипт устанавливается автоматически на
 * первом запуске, так что после открытия экрана его карточка обязана
 * присутствовать. Через [ReadmeScreenshotCapture.captureViewFullContent] —
 * полный контент включая RecyclerView со всеми preset-карточками и footer.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerScreenshotE2ETest : E2ETestBase() {

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
    fun captureScriptManagerScreenshot() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val scripts = GameScreen(scenario, idling)
            .waitForLoaded()
            .openSettings()
            .openManageScripts()
        scripts.waitForCard("SBG Vanilla+")

        var fragmentView: View? = null
        scenario.onActivity { activity ->
            fragmentView = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
                ?.requireView()
        }
        ReadmeScreenshotCapture.captureViewFullContent(
            "script-manager",
            fragmentView ?: error("ScriptListFragment.view не найден"),
        )
    }
}
