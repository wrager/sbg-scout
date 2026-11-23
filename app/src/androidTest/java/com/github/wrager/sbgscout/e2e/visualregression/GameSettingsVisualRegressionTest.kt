package com.github.wrager.sbgscout.e2e.visualregression

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import com.github.wrager.sbgscout.e2e.screenshots.ScrollStitchCapture
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Публичная mock-фикстура обеспечивает одинаковый VR baseline в CI и локально,
 * не завися от наличия приватного snapshot игры.
 * Проверяется контекст инжекции Scout; вариант light соответствует фиксированным стилям фикстуры.
 *
 * 🤖 Generated with GPT-6.1 Sol
 */
@RunWith(AndroidJUnit4::class)
class GameSettingsVisualRegressionTest : E2ETestBase() {
    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
        ThemeOverride.forceLightTheme()
        StatusBarDemoMode.enable()
    }

    @After
    fun tearDownVisualRegression() {
        StatusBarDemoMode.disable()
        ThemeOverride.resetTheme()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @VisualRegressionTest
    fun captureGameSettingsLight() {
        val game = openGameSettings(AssetLoader.read("fixtures/app-page-with-settings-content-realistic.html"))
        prepareScreenshot(game)
        VisualRegressionCapture.captureFullNative("game-settings-light")
    }

    @Test
    @VisualRegressionTest
    fun captureGameSettingsAfterDelayedFrame() {
        val body =
            AssetLoader
                .read("fixtures/app-page-with-settings-content-realistic.html")
                .replace("</head>", "<style>.settings { visibility: hidden; }</style></head>")
        val game = openGameSettings(body)
        prepareScreenshot(game)
        val webView = game.webView()
        val bounds = settingsTitleBounds(webView)
        assertFalse("Скрытый заголовок не должен присутствовать в начальном кадре", settingsTitleIsDrawn(bounds))

        // Отменяем draw, не блокируя JS и UI-поток: готовый DOM ещё не гарантирует новый кадр.
        val drawAfter = SystemClock.uptimeMillis() + DELAYED_FRAME_MS
        val delayedDraw = ViewTreeObserver.OnPreDrawListener { SystemClock.uptimeMillis() >= drawAfter }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val domReady = CountDownLatch(1)
        val visibility = AtomicReference("")
        try {
            // ActivityScenario.onActivity ждёт idle и снял бы задержку до проверки.
            instrumentation.runOnMainSync {
                webView.viewTreeObserver.addOnPreDrawListener(delayedDraw)
                webView.evaluateJavascript(
                    "(function(){var settings=document.querySelector('.settings');" +
                        "settings.style.visibility='visible';return getComputedStyle(settings).visibility;})()",
                ) {
                    visibility.set(it)
                    domReady.countDown()
                }
            }
            assertTrue(
                "DOM не стал готов во время задержки кадра",
                domReady.await(INJECT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            assertEquals("\"visible\"", visibility.get())
            assertTrue(
                "Кадр должен оставаться задержанным после готовности DOM",
                SystemClock.uptimeMillis() < drawAfter,
            )
            waitForScreenshotFrame(webView)
            assertTrue("DOM готов, но заголовок настроек ещё не попал в кадр", settingsTitleIsDrawn(bounds))
            VisualRegressionCapture.captureFullNative("game-settings-light")
        } finally {
            instrumentation.runOnMainSync { webView.viewTreeObserver.removeOnPreDrawListener(delayedDraw) }
        }
    }

    private fun openGameSettings(body: String): GameScreen {
        // SVP меняет mock после своей инициализации; здесь проверяется только инжекция Scout.
        ScriptStorageFixture.storage().save(
            ScriptStorageFixture.minimalScript(
                name = PresetScripts.SVP.displayName,
                sourceUrl = PresetScripts.SVP.downloadUrl,
                isPreset = true,
            ),
        )
        server.gamePageBody = body
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val game = GameScreen(launchGameActivity(), idling).waitForLoaded()
        waitForButtonInjected(game)
        return game
    }

    private fun prepareScreenshot(game: GameScreen) {
        game.hideTransientChromeForScreenshot()
        // Скроллим popup так, чтобы наша кнопка оказалась в начале viewport'а
        // popup'а - так состояние popup'а детерминировано между запусками
        // (без scroll'а stale state может оставаться от предыдущей загрузки
        // того же процесса).
        game.evaluateJs(SCROLL_TO_BUTTON_SCRIPT)
        waitForScreenshotFrame(game.webView())
    }

    private fun waitForScreenshotFrame(webView: WebView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertTrue(
                "WebView должен быть видимым и использовать GPU",
                webView.isShown && webView.isHardwareAccelerated,
            )
            // VisualStateCallback сообщает готовность к draw; отдельно ждём commit кадра.
            webView.postVisualStateCallback(
                SystemClock.uptimeMillis(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        webView.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
                        webView.invalidate()
                    }
                },
            )
        }
        assertTrue(
            "WebView не отправил готовый кадр за ${FRAME_TIMEOUT_MS}ms",
            rendered.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        instrumentation.waitForIdleSync()
    }

    private fun settingsTitleBounds(webView: WebView): Rect =
        ReadmeScreenshotCapture.webBoundsInScreen(
            webView,
            """
            (function() {
                var rect = document.querySelector('.settings h3').getBoundingClientRect();
                return JSON.stringify({left: rect.left, top: rect.top, width: rect.width, height: rect.height});
            })()
            """.trimIndent(),
        )

    private fun settingsTitleIsDrawn(bounds: Rect): Boolean {
        val bitmap = ScrollStitchCapture.takeScreenshot()
        try {
            val pixels = IntArray(bounds.width() * bounds.height())
            bitmap.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
            return pixels.any { it == Color.BLACK }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitForButtonInjected(game: GameScreen) {
        val deadline = SystemClock.uptimeMillis() + INJECT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val raw =
                game.evaluateJs(
                    "(function(){var b=document.getElementById('sbg-scout-settings-btn');" +
                        "if(!b)return null;var r=b.getBoundingClientRect();" +
                        "return r.width>0&&r.height>0?'ok':null;})()",
                )
            if (raw != "null") return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Кнопка sbg-scout-settings-btn не появилась/не отрендерилась за ${INJECT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val INJECT_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
        const val FRAME_TIMEOUT_MS = 10_000L
        const val DELAYED_FRAME_MS = 5_000L

        // Скроллит popup так, чтобы наша инжектированная кнопка стала верхним
        // элементом видимой области popup'а. Используется тот же scroll-якорь,
        // что в GameSettingsScreenshotE2ETest, но без вычисления crop-rect
        // (VR captures full screen).
        private val SCROLL_TO_BUTTON_SCRIPT = """
            (function() {
                var btn = document.getElementById('sbg-scout-settings-btn');
                if (!btn) return null;
                var ourItem = btn.closest('.settings-section__item') || btn.parentElement;
                if (!ourItem) return null;
                var node = ourItem.parentElement;
                while (node && node !== document.body) {
                    var st = window.getComputedStyle(node);
                    if ((st.overflowY === 'auto' || st.overflowY === 'scroll') &&
                            node.scrollHeight > node.clientHeight) {
                        node.scrollTop = ourItem.offsetTop - node.offsetTop;
                        break;
                    }
                    node = node.parentElement;
                }
                btn.scrollIntoView({block: 'start'});
                return 'ok';
            })()
        """
    }
}
