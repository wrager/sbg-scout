package com.github.wrager.sbgscout.e2e.screenshots

import android.graphics.Rect
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Снимает game_loading.png - экран загрузки игры: надпись "Загрузка...",
 * "Настройки SBG Scout" и "Reload" внизу экрана и индикатор загрузки страницы
 * в правом нижнем углу. Системные панели в снимок не входят.
 *
 * Фикстура app-page-loading-screen-realistic.html не убирает экран загрузки игры.
 * Индикатор виден, только пока грузятся ресурсы страницы, поэтому на страницу
 * добавлена картинка, на которую сервер отвечает лишь после снимка.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
@RunWith(AndroidJUnit4::class)
class GameLoadingScreenshotE2ETest : E2ETestBase() {
    private val pendingImageReleased = CountDownLatch(1)

    @Before
    fun setUpLocale() {
        ReadmeScreenshotCapture.forceRussianLocale()
    }

    @After
    fun tearDownScreenshot() {
        pendingImageReleased.countDown()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @ReadmeScreenshot
    fun captureGameLoadingScreenshot() {
        server.gamePageBody =
            AssetLoader
                .read("fixtures/app-page-loading-screen-realistic.html")
                .replace("</body>", "<img src=\"$PENDING_IMAGE_PATH\" alt=\"\" style=\"$PENDING_IMAGE_STYLE\"></body>")
        server.stubResponse(PENDING_IMAGE_PATH) {
            pendingImageReleased.await(PENDING_IMAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            MockResponse().setResponseCode(404)
        }
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        GameScreen(scenario, idling)
            .waitForFakePage("app")
            .waitForLoadingUi(LoadingUiState.LOADING)
            .waitForNativeButtonsInPlace()
            .waitForPageLoadingIndicatorInPlace()
        Thread.sleep(RENDER_SETTLE_MS)

        ReadmeScreenshotCapture.captureRegion("game_loading", contentRegion(scenario))
    }

    private fun contentRegion(scenario: ActivityScenario<GameActivity>): Rect {
        var region: Rect? = null
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val bars = requireNotNull(decor.rootWindowInsets).getInsets(WindowInsets.Type.systemBars())
            region = Rect(bars.left, bars.top, decor.width - bars.right, decor.height - bars.bottom)
        }
        return checkNotNull(region) { "GameActivity недоступна" }
    }

    private companion object {
        const val PENDING_IMAGE_PATH = "/screenshot/pending-image"

        // В потоке страницы картинка удлинила бы её, и WebView показал бы полосу прокрутки.
        const val PENDING_IMAGE_STYLE = "position:absolute;top:0;left:0;width:1px;height:1px"
        const val PENDING_IMAGE_TIMEOUT_MS = 30_000L
        const val RENDER_SETTLE_MS = 500L
    }
}
