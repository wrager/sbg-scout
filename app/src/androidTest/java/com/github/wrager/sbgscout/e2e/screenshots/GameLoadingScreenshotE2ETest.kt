package com.github.wrager.sbgscout.e2e.screenshots

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import com.github.wrager.sbgscout.e2e.visualregression.ThemeOverride
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Снимает game_loading.png - экран загрузки игры: надпись "Загрузка...",
 * "Настройки SBG Scout" и "Reload" между центром и нижней частью экрана,
 * индикатор загрузки страницы в правом нижнем углу. Системные панели в снимок не входят.
 *
 * Фикстура app-page-loading-screen-realistic.html не убирает экран загрузки игры.
 * Индикатор виден, только пока грузятся ресурсы страницы, поэтому на страницу
 * добавлена картинка, на которую сервер отвечает лишь после снимка.
 *
 * 🤖 Generated with GPT-6 Sol
 */
@RunWith(AndroidJUnit4::class)
class GameLoadingScreenshotE2ETest : E2ETestBase() {
    private val pendingImageReleased = CountDownLatch(1)
    private var savedAnimatorDurationScale: String? = null
    private var animatorDurationScaleSaved = false

    @Before
    fun setUpScreenshot() {
        ReadmeScreenshotCapture.forceRussianLocale()
        ThemeOverride.forceLightTheme()
        val contentResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        savedAnimatorDurationScale = Settings.Global.getString(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        animatorDurationScaleSaved = true
        // В тестовом эмуляторе анимации отключены; крутилка иначе застывает в форме Reload.
        // 🤖 Generated with GPT-6 Sol
        setAnimatorDurationScale("1")
    }

    @After
    fun tearDownScreenshot() {
        pendingImageReleased.countDown()
        try {
            if (animatorDurationScaleSaved) {
                setAnimatorDurationScale(savedAnimatorDurationScale)
            }
        } finally {
            try {
                ThemeOverride.resetTheme()
            } finally {
                ReadmeScreenshotCapture.resetLocale()
            }
        }
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
        val game = GameScreen(scenario, idling)
        game
            .waitForFakePage("app")
            .waitForLoadingUi(LoadingUiState.LOADING)
            .waitForNativeButtonsInPlace()
            .waitForPageLoadingIndicatorInPlace()
        val steps =
            JSONArray(
                game.evaluateJs(
                    "(function(){var rows=document.querySelectorAll('.loading-screen__task');" +
                        "var first=rows[0].getBoundingClientRect();var second=rows[1].getBoundingClientRect();" +
                        "return [first.left/innerWidth,first.top/innerHeight,second.top-first.top];})()",
                ),
            )
        assertEquals("Отступ этапов загрузки слева", 0.064, steps.getDouble(0), 0.02)
        assertEquals("Отступ этапов загрузки сверху", 0.26, steps.getDouble(1), 0.02)
        assertEquals("Интервал этапов загрузки", 22.0, steps.getDouble(2), 3.0)
        Thread.sleep(RENDER_SETTLE_MS)
        assertSpinnerAnimates(scenario)

        ReadmeScreenshotCapture.captureRegion("game_loading", contentRegion(scenario), nativeResolution = true)
    }

    private fun assertSpinnerAnimates(scenario: ActivityScenario<GameActivity>) {
        val initialFrame = indicatorFrame(scenario)
        try {
            var moved = false
            repeat(5) {
                Thread.sleep(SPINNER_FRAME_INTERVAL_MS)
                val nextFrame = indicatorFrame(scenario)
                try {
                    if (!initialFrame.sameAs(nextFrame)) moved = true
                } finally {
                    nextFrame.recycle()
                }
            }
            assertTrue("Индикатор загрузки должен вращаться", moved)
        } finally {
            initialFrame.recycle()
        }
    }

    private fun indicatorFrame(scenario: ActivityScenario<GameActivity>): Bitmap {
        var frame: Bitmap? = null
        scenario.onActivity { activity ->
            val indicator = activity.findViewById<View>(R.id.pageLoadingIndicator)
            val bitmap = Bitmap.createBitmap(indicator.width, indicator.height, Bitmap.Config.ARGB_8888)
            indicator.draw(Canvas(bitmap))
            frame = bitmap
        }
        return checkNotNull(frame) { "Индикатор загрузки недоступен" }
    }

    private fun setAnimatorDurationScale(value: String?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(
                Settings.Global.putString(
                    instrumentation.targetContext.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    value,
                ),
            )
        } finally {
            automation.dropShellPermissionIdentity()
        }
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
        const val SPINNER_FRAME_INTERVAL_MS = 100L
    }
}
