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
 * Источник DOM:
 * - Если есть `fixtures/game-snapshot.html` (генерируется gradle-таском
 *   `inlineGameSnapshot` из локального snapshot реальной страницы игры в
 *   `refs/game/private/`) — берётся он, рендер близок к реальной игре.
 * - Иначе fallback на mock-фикстуру
 *   `fixtures/app-page-with-settings-content-realistic.html` — приближение
 *   по тёмной теме из `refs/game/css/variables.css` и структуре из
 *   `refs/game/dom/game-body.html`.
 *
 * Crop: наша инжектированная `.settings-section__item` + первая секция настроек
 * игры (h4 + 2-3 пункта). Полный список настроек игры на скриншоте не нужен -
 * README показывает компактный пример "вот так наша кнопка встроена".
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
        server.gamePageBody =
            AssetLoader.readOrNull("fixtures/game-snapshot.html")
                ?: AssetLoader.read("fixtures/app-page-with-settings-content-realistic.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        waitForButtonInjected(game)

        var webView: WebView? = null
        scenario.onActivity { webView = it.webView }
        val wv = webView ?: error("WebView не найден в GameActivity")
        // На полном snapshot настройки игры могут быть длиннее viewport - наша
        // кнопка может оказаться за пределами видимой области. Скроллим к ней
        // ДО измерения bounding rect, иначе crop вернёт область за viewport
        // и captureRegion вырежет пустоту.
        ReadmeScreenshotCapture.webBoundsInScreen(
            wv,
            "(function(){var b=document.getElementById('sbg-scout-settings-btn');" +
                "if(b)b.scrollIntoView({block:'start'});" +
                "return JSON.stringify({left:0,top:0,width:1,height:1});})()",
        )
        Thread.sleep(SCROLL_SETTLE_MS)
        val region = ReadmeScreenshotCapture.webBoundsInScreen(wv, CROP_RECT_SCRIPT)
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
        const val SCROLL_SETTLE_MS = 200L

        // Возвращает rect от нашего инжектированного .settings-section__item
        // до конца первой следующей .settings-section (h4 + первые 2 пункта).
        // Если nextElementSibling - не секция, а другой item, или такого нет
        // (mock-фикстура с одной секцией) - возвращает rect самого .settings-content.
        private val CROP_RECT_SCRIPT = """
            (function() {
                var btn = document.getElementById('sbg-scout-settings-btn');
                if (!btn) return JSON.stringify({error: 'no scout button'});
                var ourItem = btn.closest('.settings-section__item') || btn.parentElement;
                var content = ourItem.parentElement;
                if (!content) return JSON.stringify({error: 'no settings-content'});
                var ourRect = ourItem.getBoundingClientRect();
                var nextSection = ourItem.nextElementSibling;
                var endRect = ourRect;
                if (nextSection && nextSection.classList.contains('settings-section')) {
                    var sectionChildren = nextSection.children;
                    var lastIdx = Math.min(sectionChildren.length - 1, 2);
                    if (lastIdx >= 0) {
                        endRect = sectionChildren[lastIdx].getBoundingClientRect();
                    } else {
                        endRect = nextSection.getBoundingClientRect();
                    }
                } else {
                    var contentRect = content.getBoundingClientRect();
                    endRect = { right: contentRect.right, bottom: contentRect.bottom };
                }
                var left = ourRect.left;
                var top = ourRect.top;
                var right = Math.max(ourRect.right, endRect.right);
                var bottom = endRect.bottom;
                return JSON.stringify({
                    left: left,
                    top: top,
                    width: right - left,
                    height: bottom - top
                });
            })()
        """
    }
}
