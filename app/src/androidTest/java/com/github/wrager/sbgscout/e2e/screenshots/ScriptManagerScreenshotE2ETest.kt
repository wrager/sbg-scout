package com.github.wrager.sbgscout.e2e.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает script-manager.png — список скриптов (ScriptListFragment) внутри
 * settingsContainer.
 *
 * Стартовое состояние, отражённое на скриншоте:
 * - SVP (SBG Vanilla+) - bundled, ставится автоматически BundledScriptInstaller'ом
 *   при первом запуске GameActivity, enabled.
 * - EUI (SBG Enhanced UI) - принудительно скачивается тестом через
 *   FakeGameServer (stubScriptAsset), после download остаётся disabled
 *   (нет enabledByDefault у preset'а).
 * - CUI (SBG CUI) - не установлен, показан с download-иконкой.
 *
 * Через [ReadmeScreenshotCapture.captureFullScreenWithScroll] - длинный
 * stitched-PNG с status bar, footer и тенями карточек.
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
        // После загрузки adapter биндит карточку EUI с новой UI-секцией
        // (toggle вместо download-иконки) асинхронно через Flow.collect.
        // Дополнительно дожидаемся, пока исчезнет Snackbar "Скрипт ... добавлен"
        // (Snackbar.LENGTH_LONG ~3.5s): без этого toast виден на одних
        // frame'ах stitch-захвата и отсутствует на других, pixel-match
        // overlap detection даёт 0, и в stitched дублируется весь viewport.
        Thread.sleep(POST_INSTALL_SETTLE_MS)

        ReadmeScreenshotCapture.captureFullScreenWithScroll(
            "script-manager",
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
