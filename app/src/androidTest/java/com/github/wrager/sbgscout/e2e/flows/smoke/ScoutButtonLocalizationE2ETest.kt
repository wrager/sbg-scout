package com.github.wrager.sbgscout.e2e.flows.smoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет локализацию HTML-кнопки, которую ScoutBridge.BOOTSTRAP_SCRIPT
 * инжектирует в `.settings-content`.
 *
 * Контракт: при `localStorage['settings'].lang === 'sys'` pickStrings должен
 * резолвить язык через `navigator.language` (так же как делает сама игра в
 * `script.js:38` через `getLanguage()`), а не уходить на английский fallback.
 *
 * Фикстура `app-page-with-settings-content.html` подменяет navigator.language
 * на `ru-RU` и пишет `{lang:'sys'}` в localStorage до срабатывания bootstrap,
 * поэтому тест не зависит от локали эмулятора.
 */
@RunWith(AndroidJUnit4::class)
class ScoutButtonLocalizationE2ETest : E2ETestBase() {

    @Test
    fun injectedButton_usesRussian_whenLangIsSysAndNavigatorLanguageIsRu() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-settings-content.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        // Bootstrap инжектирует кнопку на DOMContentLoaded через checkReady →
        // onReady → injectButton. evaluateJavascript возвращает результат как
        // JSON-строку в двойных кавычках, либо литерал "null".
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val raw = game.evaluateJs(
                "(function(){var b=document.getElementById('sbg-scout-settings-btn');" +
                    "return b?b.textContent:null;})()",
            )
            if (raw != "null") {
                assertEquals("\"Открыть\"", raw)
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Кнопка sbg-scout-settings-btn не появилась в DOM за ${TIMEOUT_MS}ms")
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
