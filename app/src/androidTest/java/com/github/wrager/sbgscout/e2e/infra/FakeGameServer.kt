package com.github.wrager.sbgscout.e2e.infra

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Обёртка над [MockWebServer] с маршрутизацией под fake-игру.
 *
 * Сервер стартует на `127.0.0.1:<random-port>` в том же процессе, что и
 * приложение под тест (instrumented tests — один процесс), поэтому WebView
 * обращается на этот же loopback без нужды в `10.0.2.2`.
 *
 * Тела HTML-страниц задаются полями [gamePageBody] / [loginPageBody] до старта
 * сервера (или между запросами — dispatcher читает свежее значение).
 */
class FakeGameServer {

    private val server = MockWebServer()

    @Volatile var gamePageBody: String = "<html><body>fake app page (no body set)</body></html>"

    @Volatile var loginPageBody: String = "<html><body>fake login (no body set)</body></html>"

    /** Базовый URL вида `http://127.0.0.1:<port>`. Без завершающего слеша. */
    val baseUrl: String
        get() = "http://${server.hostName}:${server.port}"

    fun start() {
        server.dispatcher = FakeGameDispatcher(this)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun shutdown() {
        server.shutdown()
    }

    fun requestCount(): Int = server.requestCount

    /** Блокирующее чтение следующего запроса, пришедшего на fake-сервер. */
    fun takeRequest(timeoutMs: Long = DEFAULT_TAKE_TIMEOUT_MS): RecordedRequest? =
        server.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)

    private companion object {
        const val DEFAULT_TAKE_TIMEOUT_MS = 5_000L
    }
}
