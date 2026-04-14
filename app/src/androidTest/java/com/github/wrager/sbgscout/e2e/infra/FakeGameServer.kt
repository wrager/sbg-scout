package com.github.wrager.sbgscout.e2e.infra

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Обёртка над [MockWebServer] с маршрутизацией под fake-игру и fake-GitHub.
 *
 * Сервер стартует на `127.0.0.1:<random-port>` в том же процессе, что и
 * приложение под тест (instrumented tests — один процесс), поэтому WebView
 * и HttpFetcher обращаются на этот же loopback без нужды в `10.0.2.2`.
 *
 * Настройка ответов:
 * - HTML игры и логина — поля [gamePageBody] / [loginPageBody].
 * - Скачивание юзерскриптов с GitHub — [stubScriptAsset] по комбинации
 *   owner/repo/tag/filename. Dispatcher перенаправляет запросы GitHub
 *   (переписанные через [DefaultHttpFetcher.urlRewriter] на префикс `/gh-web/`)
 *   в эту Map.
 * - GitHub API — [stubGithubReleaseLatest] / [stubGithubReleases] для
 *   `/gh-api/repos/<owner>/<repo>/releases[/latest]`.
 */
class FakeGameServer {

    private val server = MockWebServer()

    @Volatile var gamePageBody: String = "<html><body>fake app page (no body set)</body></html>"

    @Volatile var loginPageBody: String = "<html><body>fake login (no body set)</body></html>"

    /** Содержимое юзерскриптов: ключ = "<owner>/<repo>/<tag>/<filename>". */
    internal val scriptAssets = ConcurrentHashMap<String, String>()

    /** JSON для `/gh-api/repos/<owner>/<repo>/releases/latest`, ключ = "<owner>/<repo>". */
    internal val githubLatestReleaseJson = ConcurrentHashMap<String, String>()

    /** JSON для `/gh-api/repos/<owner>/<repo>/releases`, ключ = "<owner>/<repo>". */
    internal val githubReleasesListJson = ConcurrentHashMap<String, String>()

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

    /**
     * Регистрирует содержимое `<filename>`-asset для указанного релиза, чтобы
     * dispatcher вернул его на запрос `github.com/<owner>/<repo>/releases/<tag>/download/<filename>`
     * или `github.com/<owner>/<repo>/releases/latest/download/<filename>`.
     *
     * @param tag — либо конкретный тег вроде "v0.8.0", либо специальная строка "latest"
     *   для запросов `/releases/latest/download/`.
     */
    fun stubScriptAsset(
        owner: String,
        repo: String,
        tag: String,
        filename: String,
        content: String,
    ) {
        scriptAssets["$owner/$repo/$tag/$filename"] = content
    }

    /** Регистрирует ответ для `/gh-api/repos/<owner>/<repo>/releases/latest`. */
    fun stubGithubReleaseLatest(owner: String, repo: String, json: String) {
        githubLatestReleaseJson["$owner/$repo"] = json
    }

    /** Регистрирует ответ для `/gh-api/repos/<owner>/<repo>/releases`. */
    fun stubGithubReleasesList(owner: String, repo: String, json: String) {
        githubReleasesListJson["$owner/$repo"] = json
    }

    private companion object {
        const val DEFAULT_TAKE_TIMEOUT_MS = 5_000L
    }
}
