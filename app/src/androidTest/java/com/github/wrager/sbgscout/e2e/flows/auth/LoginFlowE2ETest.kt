package com.github.wrager.sbgscout.e2e.flows.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Полный flow без pre-auth:
 *
 * 1. WebView открывает `/app` без session-cookie.
 * 2. Fake-сервер отвечает 302 на `/login`.
 * 3. WebView грузит fake-страницу логина (HTML из androidTest/assets/fixtures).
 * 4. Тест вызывает `submitTelegramStub()` через LoginScreen → JS делает POST
 *    `/login/callback`, dispatcher отвечает 302 + Set-Cookie session-токена.
 * 5. JS после fetch делает `window.location.href = "/app"` — WebView повторно
 *    обращается к `/app`, теперь уже с cookies.
 * 6. Fake-сервер видит cookie и отдаёт HTML игры → onGamePageFinished →
 *    IdlingResource становится idle → GameScreen.waitForLoaded возвращается.
 *
 * Проверяется вся HTTP-цепочка через server.takeRequest + итоговое состояние
 * через evaluateJs на фикстуре.
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowE2ETest : E2ETestBase() {

    @Test
    fun fakeTelegramAuth_redirectsUnauthorizedUserIntoGame() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        server.loginPageBody = AssetLoader.read("fixtures/login-page-minimal.html")
        // cookies НЕ инжектируем — сценарий «первый вход, сессии ещё нет».

        val scenario = launchGameActivity()

        val unauthenticatedAppRequest = server.takeRequest()
        assertNotNull("WebView должен обратиться к fake-серверу", unauthenticatedAppRequest)
        assertEquals("/app", unauthenticatedAppRequest!!.path)
        assertFalse(
            "На первом запросе /app не должно быть session-cookie",
            unauthenticatedAppRequest.getHeader("Cookie").orEmpty().contains(
                "hittoken=${CookieFixtures.FAKE_SESSION_TOKEN}",
            ),
        )

        val loginRequest = server.takeRequest()
        assertNotNull("После 302 WebView должен запросить /login", loginRequest)
        assertEquals("/login", loginRequest!!.path)

        // Fake-страница /login загружена, JS фикстуры исполнен — дожидаемся.
        val loginScreen = LoginScreen(scenario).waitUntilReady()
        loginScreen.submitFakeAuth()

        val callbackRequest = server.takeRequest()
        assertNotNull(
            "submitTelegramStub должен отправить POST на /login/callback",
            callbackRequest,
        )
        assertEquals("POST", callbackRequest!!.method)
        assertEquals("/login/callback", callbackRequest.path)

        // Dispatcher вернул 302 → /app + Set-Cookie. WebView сам перейдёт на /app
        // (см. submitTelegramStub.then → location.href = "/app").
        val authenticatedAppRequest = server.takeRequest()
        assertNotNull("После успешного логина WebView должен заново загрузить /app", authenticatedAppRequest)
        assertEquals("/app", authenticatedAppRequest!!.path)
        assertTrue(
            "На втором запросе /app должен быть session-cookie, выставленный dispatcher'ом",
            authenticatedAppRequest.getHeader("Cookie").orEmpty().contains(
                "hittoken=${CookieFixtures.FAKE_SESSION_TOKEN}",
            ),
        )

        // Финальная проверка через IdlingResource + evaluateJs: мы действительно
        // на fake-странице игры после логина.
        val game = GameScreen(scenario, idling).waitForLoaded()
        assertEquals("\"app\"", game.evaluateJs("window.__sbgFakePage"))
    }
}
