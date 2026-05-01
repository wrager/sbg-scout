package com.github.wrager.sbgscout.e2e.screenshots

import android.view.View
import androidx.recyclerview.widget.RecyclerView
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
 * присутствовать. Через [ReadmeScreenshotCapture.captureFullScreenWithScroll] —
 * длинный stitched-PNG с status bar, footer и тенями карточек.
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

        var recyclerView: RecyclerView? = null
        scenario.onActivity { activity ->
            val container = activity.findViewById<View>(R.id.settingsContainer)
            recyclerView = findRecyclerView(container, R.id.scriptList)
        }
        ReadmeScreenshotCapture.captureFullScreenWithScroll(
            "script-manager",
            recyclerView ?: error("ScriptList RecyclerView не найден"),
        )
    }

    private fun findRecyclerView(root: View?, id: Int): RecyclerView? {
        if (root == null) return null
        if (root.id == id && root is RecyclerView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findRecyclerView(root.getChildAt(i), id)
                if (match != null) return match
            }
        }
        return null
    }
}
