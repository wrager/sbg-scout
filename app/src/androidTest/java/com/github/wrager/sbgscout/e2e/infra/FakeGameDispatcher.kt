package com.github.wrager.sbgscout.e2e.infra

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * Маршрутизатор fake-сервера игры.
 *
 * Поведение по путям:
 * - `GET /app` и подпути: если в куке нет `hittoken` — 302 на `/login`;
 *   иначе — HTML из [FakeGameServer.gamePageBody].
 * - `GET /login`: HTML логина из [FakeGameServer.loginPageBody].
 * - `POST /login/callback`: 302 на `/app` + Set-Cookie для session-токена.
 * - `GET /favicon.ico`: пустой 204.
 * - Любой другой путь: 404.
 *
 * Тело страниц хранится в [FakeGameServer] и задаётся тестом перед стартом.
 */
class FakeGameDispatcher(
    private val server: FakeGameServer,
) : Dispatcher() {

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: return notFound()
        val method = request.method ?: "GET"

        return when {
            method == "GET" && path.startsWith("/app") -> handleAppPage(request)
            method == "GET" && path.startsWith("/login") -> loginPage()
            method == "POST" && path.startsWith("/login/callback") -> loginCallback()
            path == "/favicon.ico" -> MockResponse().setResponseCode(204)
            else -> notFound()
        }
    }

    private fun handleAppPage(request: RecordedRequest): MockResponse {
        val cookieHeader = request.getHeader("Cookie").orEmpty()
        val hasSession = cookieHeader.contains("hittoken=${CookieFixtures.FAKE_SESSION_TOKEN}")
        return if (hasSession) {
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(server.gamePageBody)
        } else {
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/login")
                .setBody("")
        }
    }

    private fun loginPage(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody(server.loginPageBody)

    private fun loginCallback(): MockResponse =
        MockResponse()
            .setResponseCode(302)
            .setHeader("Location", "/app")
            .addHeader("Set-Cookie", "hittoken=${CookieFixtures.FAKE_SESSION_TOKEN}; Path=/")
            .addHeader("Set-Cookie", "PHPSESSID=e2e_session; Path=/")
            .setBody("")

    private fun notFound(): MockResponse =
        MockResponse().setResponseCode(404).setBody("not found")
}
