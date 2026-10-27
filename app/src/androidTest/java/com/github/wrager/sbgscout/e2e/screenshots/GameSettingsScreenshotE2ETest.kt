package com.github.wrager.sbgscout.e2e.screenshots

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Приватный snapshot при наличии сохраняет реалистичный вид README, а публичный
 * mock обеспечивает контекст игровых настроек в CI без приватных файлов.
 * Заголовок, язык и тема проверяются внутри crop, чтобы снимок не потерял окружение кнопки Scout.
 *
 * 🤖 Generated with GPT-6.1 Sol
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
        captureGameSettings(
            AssetLoader.readOrNull("fixtures/game-snapshot.html")
                ?: AssetLoader.read("fixtures/app-page-with-settings-content-realistic.html"),
            saveScreenshot = true,
        )
    }

    @Test
    fun fallbackRetainsGameSettingsContext() {
        captureGameSettings(
            AssetLoader.read("fixtures/app-page-with-settings-content-realistic.html"),
            saveScreenshot = false,
        )
    }

    private fun captureGameSettings(
        body: String,
        saveScreenshot: Boolean,
    ) {
        server.gamePageBody = body
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        waitForButtonInjected(game)

        game.hideTransientChromeForScreenshot()
        Thread.sleep(RENDER_SETTLE_MS)
        val wv = game.webView()

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
        assertGameSettingsContextInFrame(game, region)
        if (saveScreenshot) ReadmeScreenshotCapture.captureRegion("game_settings", region)
    }

    private fun assertGameSettingsContextInFrame(
        game: GameScreen,
        region: Rect,
    ) {
        val context =
            JSONArray(
                game.evaluateJs(
                    """
                    (function() {
                        var title = document.querySelector('.settings h3');
                        var section = document.querySelector('.settings-section__header');
                        var language = document.querySelector('select[data-setting="lang"]');
                        var theme = document.querySelector('select[data-setting="theme"]');
                        function label(select) {
                            var parent = select && select.closest('label');
                            var text = parent && parent.querySelector('span');
                            return text ? text.textContent.trim() : null;
                        }
                        function selected(select) {
                            var option = select && select.options[select.selectedIndex];
                            return option ? option.textContent.trim() : null;
                        }
                        return [title ? title.textContent.trim() : null,
                            section ? section.textContent.trim() : null,
                            label(language), label(theme), selected(language), selected(theme)];
                    })()
                    """.trimIndent(),
                ),
            )
        val expected = listOf("Настройки", "Общее", "Язык", "Тема", "Русский", "Авто")
        expected.forEachIndexed { index, text ->
            assertEquals("Контекст игровых настроек в README, элемент $index", text, context.optString(index))
        }
        val selectors =
            listOf(
                ".settings h3",
                ".settings-section__header",
                "select[data-setting=\"lang\"]",
                "select[data-setting=\"theme\"]",
                "#sbg-scout-settings-btn",
            )
        for (selector in selectors) {
            val bounds =
                ReadmeScreenshotCapture.webBoundsInScreen(
                    game.webView(),
                    """
                    (function() {
                        var element = document.querySelector(${JSONObject.quote(selector)});
                        if (!element) return JSON.stringify({error: 'Missing settings context'});
                        var rect = element.getBoundingClientRect();
                        return JSON.stringify({left: rect.left, top: rect.top,
                            width: rect.width, height: rect.height});
                    })()
                    """.trimIndent(),
                )
            assertTrue(
                "Элемент $selector должен целиком входить в README-снимок: $bounds внутри $region",
                bounds.width() > 0 && bounds.height() > 0 && region.contains(bounds),
            )
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
