package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тест: GameActivity запускается, WebView грузит fake-страницу игры,
 * JS-мосты зарегистрированы и доступны из JS-контекста.
 *
 * Проверяет не игровую логику, а только то, что связка
 * `buildType e2e → FakeGameServer → GameUrls override → WebView → bridges`
 * в принципе работает. Если этот тест падает — дальнейшие e2e не имеют смысла
 * до починки инфраструктуры.
 */
@RunWith(AndroidJUnit4::class)
class GameLoadsSmokeTest : E2ETestBase() {

    @Test
    fun gameActivity_loadsFakeApp_andBridgesAreRegistered() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        game.assertDisplayed()

        // Страница действительно из нашей фикстуры.
        assertEquals("true", game.evaluateJs("window.__sbgFakeReady"))
        assertEquals("\"app\"", game.evaluateJs("window.__sbgFakePage"))

        // JS-мосты, регистрируемые GameActivity.setupWebView, видны из JS.
        assertEquals("true", game.evaluateJs("typeof Android !== 'undefined'"))
        assertEquals("true", game.evaluateJs("typeof __sbg_share !== 'undefined'"))
        assertEquals("true", game.evaluateJs("typeof __sbg_download !== 'undefined'"))
        assertEquals("true", game.evaluateJs("typeof __sbg_settings !== 'undefined'"))
        assertEquals("true", game.evaluateJs("typeof __sbg_scout !== 'undefined'"))
    }
}
