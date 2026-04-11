package com.github.wrager.sbgscout.e2e.infra

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * Маршрутизатор fake-сервера игры + fake-GitHub.
 *
 * Game-поведение по путям:
 * - `GET /app` и подпути: если в куке нет `hittoken` — 302 на `/login`;
 *   иначе — HTML из [FakeGameServer.gamePageBody].
 * - `GET /login`: HTML логина из [FakeGameServer.loginPageBody].
 * - `POST /login/callback`: 302 на `/app` + Set-Cookie для session-токена.
 * - `GET /favicon.ico`: пустой 204.
 *
 * GitHub-поведение (через [HttpRewriterRule] / [DefaultHttpFetcher.urlRewriter]):
 * - `GET /gh-web/<owner>/<repo>/releases/latest/download/<filename>` →
 *   содержимое юзерскрипта из [FakeGameServer.scriptAssets] по ключу
 *   "<owner>/<repo>/latest/<filename>".
 * - `GET /gh-web/<owner>/<repo>/releases/download/<tag>/<filename>` →
 *   содержимое из [FakeGameServer.scriptAssets] по ключу "<owner>/<repo>/<tag>/<filename>".
 * - `GET /gh-api/repos/<owner>/<repo>/releases/latest` → JSON из
 *   [FakeGameServer.githubLatestReleaseJson].
 * - `GET /gh-api/repos/<owner>/<repo>/releases` → JSON из
 *   [FakeGameServer.githubReleasesListJson].
 * - Остальные `/gh-web`, `/gh-api`, `/raw`, `/objects` возвращают 404 с именем пути
 *   (для диагностики: тест сразу увидит, какой asset не был стаблен).
 */
class FakeGameDispatcher(
    private val server: FakeGameServer,
) : Dispatcher() {

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: return notFound("null path")
        val method = request.method ?: "GET"

        return when {
            method == "GET" && path.startsWith("/app") -> handleAppPage(request)
            method == "GET" && path.startsWith("/login") -> loginPage()
            method == "POST" && path.startsWith("/login/callback") -> loginCallback()
            path == "/favicon.ico" -> MockResponse().setResponseCode(204)
            path.startsWith("/gh-web/") -> handleGithubWebDownload(path)
            path.startsWith("/gh-api/") -> handleGithubApi(path)
            else -> notFound(path)
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

    /**
     * Обрабатывает паттерны GitHub release download:
     *   /gh-web/<owner>/<repo>/releases/latest/download/<filename>
     *   /gh-web/<owner>/<repo>/releases/download/<tag>/<filename>
     */
    private fun handleGithubWebDownload(path: String): MockResponse {
        val trimmed = path.removePrefix("/gh-web/")
        val parts = trimmed.split("/")
        // owner/repo/releases/latest/download/<filename>
        if (parts.size >= 6 && parts[2] == "releases" && parts[3] == "latest" && parts[4] == "download") {
            val key = "${parts[0]}/${parts[1]}/latest/${parts.drop(5).joinToString("/")}"
            return serveScriptAsset(key)
        }
        // owner/repo/releases/download/<tag>/<filename>
        if (parts.size >= 6 && parts[2] == "releases" && parts[3] == "download") {
            val tag = parts[4]
            val filename = parts.drop(5).joinToString("/")
            val key = "${parts[0]}/${parts[1]}/$tag/$filename"
            return serveScriptAsset(key)
        }
        return notFound("gh-web: unrecognized path $path")
    }

    private fun serveScriptAsset(key: String): MockResponse {
        val content = server.scriptAssets[key]
            ?: return notFound("gh-web: no stub for key='$key'")
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/javascript; charset=utf-8")
            .setBody(content)
    }

    /**
     * Обрабатывает паттерны GitHub REST API:
     *   /gh-api/repos/<owner>/<repo>/releases/latest
     *   /gh-api/repos/<owner>/<repo>/releases
     */
    private fun handleGithubApi(path: String): MockResponse {
        val trimmed = path.removePrefix("/gh-api/").substringBefore('?')
        val parts = trimmed.split("/")
        if (parts.size >= 5 && parts[0] == "repos" && parts[3] == "releases" && parts[4] == "latest") {
            val key = "${parts[1]}/${parts[2]}"
            val json = server.githubLatestReleaseJson[key]
                ?: return notFound("gh-api: no latest release stub for '$key'")
            return jsonOk(json)
        }
        if (parts.size >= 4 && parts[0] == "repos" && parts[3] == "releases") {
            val key = "${parts[1]}/${parts[2]}"
            val json = server.githubReleasesListJson[key]
                ?: return notFound("gh-api: no releases-list stub for '$key'")
            return jsonOk(json)
        }
        return notFound("gh-api: unrecognized path $path")
    }

    private fun jsonOk(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private fun notFound(detail: String): MockResponse =
        MockResponse().setResponseCode(404).setBody("not found: $detail")
}
