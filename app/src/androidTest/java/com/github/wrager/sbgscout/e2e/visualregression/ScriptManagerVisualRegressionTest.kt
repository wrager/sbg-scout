package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает script-manager-light.png и script-manager-dark.png - список скриптов
 * (ScriptListFragment) в обеих темах.
 *
 * Стартовое состояние повторяет [ScriptManagerScreenshotE2ETest]:
 * - SVP (SBG Vanilla+) - bundled, ставится BundledScriptInstaller'ом, enabled.
 * - EUI (SBG Enhanced UI) - принудительно скачивается через FakeGameServer,
 *   после download остаётся disabled.
 * - CUI (SBG CUI) - не установлен, с download-иконкой.
 *
 * Native-разрешение, без масштабирования.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerVisualRegressionTest : E2ETestBase() {

    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
    }

    @After
    fun tearDownVisualRegression() {
        ThemeOverride.resetTheme()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @VisualRegressionTest
    fun captureScriptManagerLight() {
        ThemeOverride.forceLightTheme()
        captureScriptManager("script-manager-light")
    }

    @Test
    @VisualRegressionTest
    fun captureScriptManagerDark() {
        ThemeOverride.forceDarkTheme()
        captureScriptManager("script-manager-dark")
    }

    private fun captureScriptManager(baselineName: String) {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        server.stubScriptAsset(
            owner = "egorantonov",
            repo = "sbg-enhanced",
            tag = "latest",
            filename = "eui.user.js",
            content = AssetLoader.read("fixtures/scripts/eui-v8.2.0.user.js"),
        )

        val scenario = launchGameActivity()
        val scripts = GameScreen(scenario, idling)
            .waitForLoaded()
            .openSettings()
            .openManageScripts()
        scripts.waitForCard("SBG Vanilla+")
        scripts.clickCardChildView("SBG Enhanced UI", R.id.actionButton)
        waitForScriptInstalled("github.com/egorantonov/sbg-enhanced")
        scripts.waitForCard("SBG Enhanced UI")
        // После загрузки EUI Snackbar "Скрипт ... добавлен" остаётся видимым
        // ~3.5s (LENGTH_LONG). На разных frame'ах scroll-stitch'а он попадает
        // в разной фазе alpha и pixel-match overlap detection даёт 0 - в
        // stitched изображение дублируется viewport. Дожидаемся исчезновения.
        Thread.sleep(POST_INSTALL_SETTLE_MS)

        VisualRegressionCapture.captureScrollableNative(
            baselineName,
            scripts.scriptListRecyclerView(),
        )
    }

    private fun waitForScriptInstalled(namespace: String) {
        val storage = ScriptStorageFixture.storage()
        val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (storage.getAll().any { it.header.namespace == namespace }) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Script $namespace не установился за ${INSTALL_TIMEOUT_MS}ms")
    }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
        const val POST_INSTALL_SETTLE_MS = 4_500L
    }
}
