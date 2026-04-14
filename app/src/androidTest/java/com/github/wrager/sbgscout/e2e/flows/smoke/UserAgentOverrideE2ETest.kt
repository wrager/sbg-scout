package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет JS-патч [com.github.wrager.sbgscout.webview.UserAgentOverride]:
 * `navigator.userAgent` внутри WebView не содержит токен `wv` (обходит
 * `IsWebView` в EUI), но настоящий `webView.settings.userAgentString`
 * и HTTP-заголовки остаются нетронутыми.
 */
@RunWith(AndroidJUnit4::class)
class UserAgentOverrideE2ETest : E2ETestBase() {

    @Test
    fun jsUserAgent_hasNoWvToken_butNativeUserAgentStringStillDoes() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        // 1. Проверка EUI IsWebView воспроизведена дословно и возвращает false.
        val euiIsWebView = game.evaluateJs("navigator.userAgent.toLowerCase().includes('wv')")
        assertEquals("false", euiIsWebView)

        // 2. Реальный UA WebView на уровне Kotlin по-прежнему содержит токен wv —
        //    сервер получает настоящий UserAgent, только JS-скрипты обмануты.
        var nativeUserAgent: String? = null
        scenario.onActivity { activity ->
            nativeUserAgent = activity.webView.settings.userAgentString
        }
        val ua = nativeUserAgent ?: error("userAgentString must not be null after setup")
        assertTrue(
            "Настоящий WebView UA должен содержать '; wv)', был: $ua",
            ua.contains("; wv)"),
        )
        // Суффикс, который добавляет GameActivity.setupWebView, тоже на месте.
        assertTrue(
            "Настоящий WebView UA должен содержать SbgScout-суффикс, был: $ua",
            ua.contains("SbgScout/"),
        )

        // 3. Suffix SbgScout виден и в JS (чтобы не потерять его случайным regex).
        val jsUa = game.evaluateJs("navigator.userAgent")
        assertTrue(
            "navigator.userAgent должен содержать SbgScout-суффикс, был: $jsUa",
            jsUa.contains("SbgScout/"),
        )
        assertFalse(
            "navigator.userAgent должен быть без токена wv, был: $jsUa",
            jsUa.lowercase().contains("wv"),
        )
    }
}
