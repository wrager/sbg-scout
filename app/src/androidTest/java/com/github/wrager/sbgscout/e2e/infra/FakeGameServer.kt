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

    /**
     * Базовый URL вида `http://127.0.0.1:<port>`. Без завершающего слеша.
     *
     * Нельзя использовать `server.hostName` — это `canonicalHostName` от
     * InetAddress.getByName("127.0.0.1"), которое делает reverse DNS lookup
     * и возвращает `"localhost"`. WebView при загрузке и SbgWebViewClient в
     * guard `GameUrls.isGameAppPage` ожидают буквально `"127.0.0.1"` (строка
     * из `GameUrls.hostMatchOverride`). Mismatch = guard не срабатывает =
     * IdlingResource никогда не становится idle = тесты виснут в timeout.
     */
    val baseUrl: String
        get() = "http://127.0.0.1:${server.port}"

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

    /**
     * Ожидает запрос, удовлетворяющий [predicate], пропуская все несоответствующие
     * (например, автоматические `/favicon.ico` от WebView — их порядок и само
     * наличие не детерминированы).
     *
     * @return первый найденный [RecordedRequest]
     * @throws AssertionError если за [timeoutMs] не пришло ни одного совпадающего
     *   запроса (в том числе если пришли только не-matching запросы)
     */
    fun takeRequestMatching(
        timeoutMs: Long = DEFAULT_TAKE_TIMEOUT_MS,
        predicate: (RecordedRequest) -> Boolean,
    ): RecordedRequest {
        val deadline = System.currentTimeMillis() + timeoutMs
        val skipped = mutableListOf<String>()
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw AssertionError(
                    "takeRequestMatching: за ${timeoutMs}ms не пришло совпадающего " +
                        "запроса. Пропущены: $skipped",
                )
            }
            val request = server.takeRequest(remaining, TimeUnit.MILLISECONDS)
                ?: throw AssertionError(
                    "takeRequestMatching: очередь пуста за ${timeoutMs}ms. " +
                        "Пропущены: $skipped",
                )
            if (predicate(request)) return request
            skipped += "${request.method} ${request.path}"
        }
    }

    private companion object {
        const val DEFAULT_TAKE_TIMEOUT_MS = 5_000L
    }
}
