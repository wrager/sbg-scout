package com.github.wrager.sbgscout.e2e.screenshots

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает game_settings.png — секцию настроек игры с инжектированной кнопкой
 * "SBG Scout".
 *
 * Реальные стили игры в репозитории не лежат, поэтому фикстура
 * `app-page-with-settings-content-realistic.html` — приближение: тёмная тема
 * по [refs/game/css/variables.css](../../../../../../../../refs/game/css/variables.css),
 * структура `.settings-content` / `.settings-section` идентична реальной игре
 * (см. [refs/game/dom/game-body.html](../../../../../../../../refs/game/dom/game-body.html)).
 * Для точного скриншота локально можно подложить полный snapshot реальной игры
 * (см. CLAUDE.md, секция "Скриншоты README").
 */
@RunWith(AndroidJUnit4::class)
class GameSettingsScreenshotE2ETest : E2ETestBase() {

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
    fun captureGameSettingsScreenshot() {
        server.gamePageBody = AssetLoader.read(
            "fixtures/app-page-with-settings-content-realistic.html",
        )
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        waitForButtonInjected(game)

        // Достаём WebView с main thread синхронно. Сам helper потом сделает
        // runOnMainSync для evaluateJavascript / getLocationOnScreen — поэтому
        // вызывать его из test thread, не изнутри onActivity (запрещено).
        var webView: WebView? = null
        scenario.onActivity { webView = it.webView }
        val region = ReadmeScreenshotCapture.webElementBoundsInScreen(
            webView ?: error("WebView не найден в GameActivity"),
            ".settings-content",
        )
        ReadmeScreenshotCapture.captureRegion("game_settings", region)
    }

    private fun waitForButtonInjected(game: GameScreen) {
        val deadline = SystemClock.uptimeMillis() + INJECT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val raw = game.evaluateJs(
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
    }
}
