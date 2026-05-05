package com.github.wrager.sbgscout.e2e.visualregression

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает game-settings-light.png - секцию настроек игры внутри WebView с
 * инжектированной кнопкой "SBG Scout".
 *
 * Только light-вариант: фикстуры (game-snapshot.html / app-page-with-settings-
 * content-realistic.html) содержат только light CSS, dark вариант потребовал
 * бы отдельной dark-фикстуры с инлайн dark-CSS - отложено в будущее.
 *
 * Захват через captureFullNative (весь экран эмулятора): popup открыт через
 * preprocessGameSnapshot (раскрытие CSS-класса hidden), наша кнопка
 * проскроллена в видимую область через scrollIntoView. Native-разрешение,
 * без масштабирования.
 */
@RunWith(AndroidJUnit4::class)
class GameSettingsVisualRegressionTest : E2ETestBase() {

    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
        ThemeOverride.forceLightTheme()
    }

    @After
    fun tearDownVisualRegression() {
        ThemeOverride.resetTheme()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @VisualRegressionTest
    fun captureGameSettingsLight() {
        server.gamePageBody =
            AssetLoader.readOrNull("fixtures/game-snapshot.html")
                ?: AssetLoader.read("fixtures/app-page-with-settings-content-realistic.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        waitForButtonInjected(game)

        game.hideTransientChromeForScreenshot()
        // Скроллим popup так, чтобы наша кнопка оказалась в начале viewport'а
        // popup'а - так состояние popup'а детерминировано между запусками
        // (без scroll'а stale state может оставаться от предыдущей загрузки
        // того же процесса).
        game.evaluateJs(SCROLL_TO_BUTTON_SCRIPT)
        Thread.sleep(RENDER_SETTLE_MS)

        VisualRegressionCapture.captureFullNative("game-settings-light")
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
        const val RENDER_SETTLE_MS = 500L

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
