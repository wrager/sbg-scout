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
        // CROP_RECT_SCRIPT сам делает scrollIntoView для нашей кнопки и сразу
        // возвращает bounding rect. Браузер при getBoundingClientRect делает
        // sync layout pass, поэтому rect соответствует послескроллочному
        // состоянию в одном tick. Раздельные scroll + измерение через два
        // отдельных evaluateJavascript давали flaky пустые скриншоты:
        // между вызовами WebView мог переотрисовать DOM, и второй вызов брал
        // rect от устаревшего layout.
        val region = ReadmeScreenshotCapture.webBoundsInScreen(wv, CROP_RECT_SCRIPT)
        check(region.width() >= MIN_RECT_PX && region.height() >= MIN_RECT_PX) {
            "Crop region слишком мал (${region.width()}x${region.height()}) - " +
                "вероятно DOM не отрендерился или элемент за viewport. " +
                "Проверь fixtures/game-snapshot.html: видна ли наша кнопка #sbg-scout-settings-btn?"
        }
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
        const val MIN_RECT_PX = 20

        // Скроллит к нашей кнопке и в том же tick возвращает rect от нашей
        // .settings-section__item до конца первой следующей .settings-section
        // (h4 + первые 2 пункта). Если nextElementSibling не секция или его
        // нет (mock-фикстура с одной секцией) - возвращает rect самого
        // .settings-content.
        //
        // Якорь - сама кнопка #sbg-scout-settings-btn (она гарантированно
        // visible после waitForButtonInjected); .settings-section__item-родитель
        // мог оказаться с display:none/visibility:hidden из-за CSS реальной
        // игры, тогда его getBoundingClientRect нулевой и crop попадёт на
        // background. Если parent-rect нулевой, fallback на rect самой кнопки.
        //
        // Дополнительно `scrollHostElement` форсит scroll нашего popup
        // (`.settings`) сам, а не через scrollIntoView на body: реальный
        // popup имеет `position: absolute` с translate(-50%, -50%), и
        // scrollIntoView через ancestor-chain до document.scrollingElement
        // ничего не двигает - окно и так на y=0. Скролл нужен внутри popup,
        // если у него установлен overflow:scroll/auto. Если нет - scroll
        // никому не нужен и rect и так корректный.
        private val CROP_RECT_SCRIPT = """
            (function() {
                var btn = document.getElementById('sbg-scout-settings-btn');
                if (!btn) return JSON.stringify({error: 'no scout button'});
                var ourItem = btn.closest('.settings-section__item') || btn.parentElement;
                if (!ourItem) return JSON.stringify({error: 'no parent of scout button'});
                // Скроллим scrollable-родителя так, чтобы наш item стал виден.
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

                var ourRect = ourItem.getBoundingClientRect();
                var btnRect = btn.getBoundingClientRect();
                if (ourRect.width === 0 || ourRect.height === 0) {
                    ourRect = btnRect;
                }
                var content = ourItem.parentElement;
                if (!content) return JSON.stringify({error: 'no settings-content'});
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
                var left = Math.max(0, ourRect.left);
                var top = Math.max(0, ourRect.top);
                var right = Math.max(ourRect.right, endRect.right);
                var bottom = endRect.bottom;
                if (right - left < 20 || bottom - top < 20) {
                    return JSON.stringify({
                        error: 'tiny rect',
                        diag: {
                            ourRect: {l: ourRect.left, t: ourRect.top, w: ourRect.width, h: ourRect.height},
                            btnRect: {l: btnRect.left, t: btnRect.top, w: btnRect.width, h: btnRect.height},
                            endRect: {r: endRect.right, b: endRect.bottom},
                            scrollY: window.scrollY,
                            viewport: {w: window.innerWidth, h: window.innerHeight}
                        }
                    });
                }
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
