package com.github.wrager.sbgscout.e2e.screenshots

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
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

        // GameActivity на старте показывает loadingOverlay поверх WebView и
        // нативные кнопки; убираются bootstrap callback'ами, но JNI-call'и не
        // всегда доходят. Скрываем руками + invalidate WebView, чтобы он
        // перерисовался в frame buffer ДО UiAutomation.takeScreenshot:
        // без invalidate UiAutomation иногда захватывает frame ПЕРЕД paint
        // pass'ом WebView, и crop region попадает на старую полу-прозрачную
        // overlay (белый прямоугольник).
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.loadingOverlay)?.visibility = View.GONE
            activity.findViewById<View>(R.id.gameInitializingLabel)?.visibility = View.GONE
            activity.findViewById<View>(R.id.reloadButton)?.visibility = View.GONE
            activity.findViewById<View>(R.id.settingsButton)?.visibility = View.GONE
            wv.invalidate()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(RENDER_SETTLE_MS)

        // CROP_RECT_SCRIPT возвращает только bottom-Y нашего content в CSS-координатах.
        // Crop в physical pixels собираем тут: full screen width, top=0
        // (захватывает status bar, как ручной скриншот в origin/main),
        // bottom = bottom-of-content в screen-coords (через WebView location +
        // density). Так получаем рамку с popup padding и status bar Android.
        val anchor = ReadmeScreenshotCapture.webBoundsInScreen(wv, CROP_RECT_SCRIPT)
        check(anchor.bottom > 0) {
            "Crop anchor имеет нулевой bottom - DOM не отрендерился или элемент " +
                "за viewport. Проверь fixtures/game-snapshot.html: видна ли наша " +
                "кнопка #sbg-scout-settings-btn?"
        }
        var screenWidth = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            screenWidth = wv.width
        }
        val region = Rect(0, 0, screenWidth, anchor.bottom)
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
        const val RENDER_SETTLE_MS = 500L

        // Скроллит к нашей кнопке и возвращает crop-anchor: top=0 (включит
        // status bar Activity), bottom = bottom первого item следующей-после-
        // первой .settings-section в CSS-координатах (для popup это: Scout
        // item + Global section целиком + Interface header + первый item
        // Interface). Возвращаемые left/width игнорируются caller'ом - он
        // делает crop по полной ширине screen, чтобы захватить popup padding
        // и border (на ручном скриншоте origin/main popup рамка видна).
        //
        // Якорь scroll - сама кнопка (visible после waitForButtonInjected).
        // closest('.settings-section__item') может вернуть элемент с
        // display:none из-за CSS игры; fallback на btn rect, если parent
        // нулевой.
        private val CROP_RECT_SCRIPT = """
            (function() {
                var btn = document.getElementById('sbg-scout-settings-btn');
                if (!btn) return JSON.stringify({error: 'no scout button'});
                var ourItem = btn.closest('.settings-section__item') || btn.parentElement;
                if (!ourItem) return JSON.stringify({error: 'no parent of scout button'});
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
                if (ourRect.width === 0 || ourRect.height === 0) {
                    ourRect = btn.getBoundingClientRect();
                }
                var bottom = ourRect.bottom;
                var firstSection = ourItem.nextElementSibling;
                if (firstSection && firstSection.classList.contains('settings-section')) {
                    var c1 = firstSection.children;
                    if (c1.length > 0) {
                        bottom = Math.max(bottom, c1[c1.length - 1].getBoundingClientRect().bottom);
                    }
                    var secondSection = firstSection.nextElementSibling;
                    if (secondSection && secondSection.classList.contains('settings-section')) {
                        var c2 = secondSection.children;
                        // h4-header + первый item следующей секции (как на ручном скрине).
                        var idx = Math.min(c2.length - 1, 1);
                        if (idx >= 0) {
                            bottom = Math.max(bottom, c2[idx].getBoundingClientRect().bottom);
                        }
                    }
                }
                return JSON.stringify({
                    left: 0,
                    top: 0,
                    width: 1,
                    height: bottom
                });
            })()
        """
    }
}
