package com.github.wrager.sbgscout.e2e.flows.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flow без pre-auth: WebView открывает `/app`, fake-сервер возвращает 302 на
 * `/login`, WebView делает запрос `/login`.
 *
 * Проверка идёт на уровне HTTP-истории fake-сервера, а не через DOM: это
 * надёжнее, чем ждать загрузки login-страницы без подписанного Idling
 * (`onGamePageFinished` срабатывает только для `/app`).
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowE2ETest : E2ETestBase() {

    @Test
    fun unauthorizedUser_isRedirectedToLoginPage() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        server.loginPageBody = AssetLoader.read("fixtures/login-page-minimal.html")
        // cookies НЕ инжектируем — dispatcher должен вернуть 302 на /login.

        launchGameActivity()

        val first = server.takeRequest()
        assertNotNull("WebView должен обратиться к fake-серверу", first)
        assertEquals("/app", first!!.path)
        assertTrue(
            "Cookie с session-токеном не должно быть без injectFakeAuth",
            !first.getHeader("Cookie").orEmpty().contains("hittoken="),
        )

        // Следующий запрос — редирект на /login.
        val second = server.takeRequest()
        assertNotNull("После 302 WebView должен запросить /login", second)
        assertEquals("/login", second!!.path)
    }
}
