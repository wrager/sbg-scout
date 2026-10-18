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
 * Принципиально использует ТОЛЬКО mock-фикстуру `app-page-with-settings-
 * content-realistic.html`, не game-snapshot.html (даже если последний есть в
 * assets). game-snapshot.html генерируется gradle-таском inlineGameSnapshot
 * из локального refs/game/private/ snapshot реальной страницы игры
 * (gitignored). Если бы VR-тест читал его при наличии, baseline в
 * .github/images/visual-regression/ содержал бы рендер настоящего игрового
 * UI - нарушение правил проекта (запрет на код/контент, похожий на код игры,
 * в открытом репо). Mock-фикстура содержит только нужный для injection
 * контейнер `.settings-content` и нашу инжекцию SBG Scout button - именно
 * то, что VR должна сторожить от регрессий.
 *
 * Захват через captureFullNative (весь экран эмулятора), наша кнопка
 * проскроллена в видимую область через scrollIntoView. Native-разрешение,
 * без масштабирования. Только light: даже mock-фикстура содержит light CSS.
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
        // ТОЛЬКО mock, не game-snapshot.html: см. KDoc класса.
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-settings-content-realistic.html")
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
