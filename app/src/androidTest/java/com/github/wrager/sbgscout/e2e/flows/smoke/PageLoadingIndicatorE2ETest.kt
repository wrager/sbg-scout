package com.github.wrager.sbgscout.e2e.flows.smoke

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Проверяет реальный прогресс Chromium; ответы удерживаются до проверки видимой крутилки. */
@RunWith(AndroidJUnit4::class)
class PageLoadingIndicatorE2ETest : E2ETestBase() {
    @Test
    fun firstDocument_showsIndicatorBeforeResponse() {
        ResponseGate().use { gate ->
            server.stubResponse("/app") { gate.respond(html(page())) }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun image_keepsIndicatorVisibleAfterGameReady() {
        assertResourceLoading("<img src='/loading/resource'>", "image/svg+xml", IMAGE) { scenario ->
            waitForViewVisibility(scenario, R.id.gameInitializingLabel, View.GONE)
            waitForIndicator(scenario, visible = true)
        }
    }

    @Test
    fun slowResponseBody_keepsIndicatorVisibleAfterHeaders() {
        ResponseGate().use { gate ->
            server.stubResponse("/app") { gate.respond(html(page()).setBodyDelay(2, TimeUnit.SECONDS)) }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            Thread.sleep(500)
            waitForIndicator(scenario, visible = true)
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun stylesheet_keepsIndicatorVisible() {
        assertResourceLoading("<link rel='stylesheet' href='/loading/resource'>", "text/css", "body { color: black; }")
    }

    @Test
    fun script_keepsIndicatorVisible() {
        assertResourceLoading(
            "<script src='/loading/resource'></script>",
            "application/javascript",
            "window.assetLoaded = true;",
        )
    }

    @Test
    fun iframe_keepsIndicatorVisible() {
        assertResourceLoading(
            "<iframe src='/loading/resource'></iframe>",
            "text/html",
            "<html><body>frame</body></html>",
        )
    }

    @Test
    fun redirectToLogin_waitsForLoginResources() {
        ResponseGate().use { gate ->
            server.stubResponse("/app") {
                MockResponse().setResponseCode(302).setHeader("Location", "/login")
            }
            server.loginPageBody = page("<img src='/loading/login-image'>")
            server.stubResponse("/loading/login-image") { gate.respond(resource("image/svg+xml", IMAGE)) }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
            scenario.onActivity { assertEquals("${server.baseUrl}/login", it.webView.url) }
        }
    }

    @Test
    fun reloadNavigationAndBack_restartIndicator() {
        val scenario = launchReadyGame()
        assertNavigationLoading(scenario, "/app") { it.webView.reload() }
        assertNavigationLoading(scenario, "/store/") { it.webView.loadUrl("${server.baseUrl}/store/") }
        assertNavigationLoading(scenario, "/app") { it.webView.goBack() }
    }

    @Test
    fun restoredActivity_tracksRestoredPageLoading() {
        val scenario = launchReadyGame()
        ResponseGate().use { gate ->
            server.stubResponse("/app") { gate.respond(html(page())) }
            scenario.recreate()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun failedResource_doesNotHideIndicatorWhileAnotherResourceIsPending() {
        ResponseGate().use { gate ->
            server.gamePageBody = page("<img src='/loading/missing'><img src='/loading/pending'>")
            CookieFixtures.injectFakeAuth(server.baseUrl)
            server.stubResponse("/loading/missing") { MockResponse().setResponseCode(404) }
            server.stubResponse("/loading/pending") { gate.respond(resource("image/svg+xml", IMAGE)) }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun mainFrameNetworkError_finishesIndicator() {
        ResponseGate().use { gate ->
            server.stubResponse("/app") {
                gate.respond(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun httpError_waitsForErrorPageResources() {
        ResponseGate().use { gate ->
            server.stubResponse("/app") {
                html(page("<img src='/loading/error-image'>")).setResponseCode(503)
            }
            server.stubResponse("/loading/error-image") { gate.respond(resource("image/svg+xml", IMAGE)) }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun stopLoading_finishesIndicator() {
        val scenario = launchReadyGame()
        ResponseGate().use { gate ->
            server.stubResponse("/loading/stopped") { gate.respond(html(page())) }
            scenario.onActivity { it.webView.loadUrl("${server.baseUrl}/loading/stopped") }
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            scenario.onActivity { it.webView.stopLoading() }
            waitForIndicator(scenario, visible = false)
        }
    }

    @Test
    fun supersededNavigation_doesNotLeaveIndicatorVisible() {
        val scenario = launchReadyGame()
        ResponseGate().use { gate ->
            server.stubResponse("/loading/superseded") { gate.respond(html(page())) }
            scenario.onActivity { it.webView.loadUrl("${server.baseUrl}/loading/superseded") }
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            scenario.onActivity { it.webView.loadUrl("${server.baseUrl}/store/") }
            waitForIndicator(scenario, visible = false)
            gate.close()
            scenario.onActivity { assertEquals("${server.baseUrl}/store/", it.webView.url) }
        }
    }

    @Test
    fun sameUrlReload_waitsForReplacementResources() {
        val scenario = launchReadyGame()
        ResponseGate().use { first ->
            ResponseGate().use { replacement ->
                val document = AtomicInteger()
                server.stubResponse("/app") {
                    html(page("<img src='/loading/image-${document.incrementAndGet()}'>"))
                }
                server.stubResponse("/loading/image-1") { first.respond(resource("image/svg+xml", IMAGE)) }
                server.stubResponse("/loading/image-2") { replacement.respond(resource("image/svg+xml", IMAGE)) }
                scenario.onActivity { it.webView.reload() }
                first.awaitRequest()
                waitForIndicator(scenario, visible = true)
                scenario.onActivity { it.webView.reload() }
                replacement.awaitRequest()
                first.close()
                Thread.sleep(200)
                waitForIndicator(scenario, visible = true)
                replacement.close()
                waitForIndicator(scenario, visible = false)
            }
        }
    }

    @Test
    fun backgroundFetchAndSameDocumentNavigation_doNotShowIndicator() {
        val scenario = launchReadyGame()
        val game = GameScreen(scenario, idling)
        ResponseGate().use { gate ->
            server.stubResponse("/loading/background") { gate.respond(resource("application/json", "{}")) }
            game.evaluateJs(
                "fetch('/loading/background'); location.hash = 'point'; history.pushState({}, '', '#next');",
            )
            gate.awaitRequest()
            // Проверяем весь интервал, пока фоновый запрос остаётся незавершённым.
            repeat(10) {
                scenario.onActivity {
                    assertEquals(View.GONE, it.findViewById<View>(R.id.pageLoadingIndicator).visibility)
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    @Test
    fun settings_coverIndicatorAndRevealItWhilePageStillLoads() {
        val scenario = launchReadyGame()
        ResponseGate().use { gate ->
            server.stubResponse("/app") { gate.respond(html(page())) }
            scenario.onActivity { it.webView.reload() }
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            scenario.onActivity {
                it.findViewById<View>(R.id.settingsButton).performClick()
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.settingsContainer).visibility)
                val indicator = it.findViewById<View>(R.id.pageLoadingIndicator)
                val settings = it.findViewById<View>(R.id.settingsContainer)
                val root = it.findViewById<android.view.ViewGroup>(R.id.rootLayout)
                assertTrue(root.indexOfChild(indicator) < root.indexOfChild(settings))
                assertTrue(!indicator.isClickable && !indicator.isFocusable)
                it.findViewById<View>(R.id.closeSettingsButton).performClick()
            }
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    private fun assertResourceLoading(
        element: String,
        contentType: String,
        body: String,
        whileResourcePending: (ActivityScenario<GameActivity>) -> Unit = {},
    ) {
        ResponseGate().use { gate ->
            server.gamePageBody = page(element)
            CookieFixtures.injectFakeAuth(server.baseUrl)
            server.stubResponse("/loading/resource") { gate.respond(resource(contentType, body)) }
            val scenario = launchGameActivity()
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            whileResourcePending(scenario)
            gate.close()
            waitForIndicator(scenario, visible = false)
        }
    }

    private fun launchReadyGame(): ActivityScenario<GameActivity> {
        server.gamePageBody = page()
        server.storePageBody = page()
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val scenario = launchGameActivity()
        waitForIndicator(scenario, visible = false)
        GameScreen(scenario, idling).waitForLoaded()
        return scenario
    }

    private fun assertNavigationLoading(
        scenario: ActivityScenario<GameActivity>,
        path: String,
        navigate: (GameActivity) -> Unit,
    ) {
        ResponseGate().use { gate ->
            server.stubResponse(path) { gate.respond(html(page())) }
            scenario.onActivity(navigate)
            gate.awaitRequest()
            waitForIndicator(scenario, visible = true)
            gate.close()
            waitForIndicator(scenario, visible = false)
            scenario.onActivity { assertEquals("${server.baseUrl}$path", it.webView.url) }
        }
    }

    private fun waitForIndicator(
        scenario: ActivityScenario<GameActivity>,
        visible: Boolean,
    ) = waitForViewVisibility(scenario, R.id.pageLoadingIndicator, if (visible) View.VISIBLE else View.GONE)

    private fun waitForViewVisibility(
        scenario: ActivityScenario<GameActivity>,
        viewId: Int,
        expected: Int,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var actual = -1
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { actual = it.findViewById<View>(viewId).visibility }
            if (actual == expected) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertEquals("View $viewId visibility", expected, actual)
    }

    private fun page(element: String = ""): String =
        AssetLoader.read("fixtures/app-page-minimal.html").replace("</body>", "$element</body>")

    private fun html(body: String): MockResponse = resource("text/html; charset=utf-8", body)

    private fun resource(
        contentType: String,
        body: String,
    ): MockResponse = MockResponse().setHeader("Content-Type", contentType).setBody(body)

    private class ResponseGate : Closeable {
        private val requested = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun respond(response: MockResponse): MockResponse {
            requested.countDown()
            check(released.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Test did not release response" }
            return response
        }

        fun awaitRequest() {
            assertTrue("WebView did not request resource", requested.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        }

        override fun close() {
            released.countDown()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 50L
        const val IMAGE =
            "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><rect width='1' height='1'/></svg>"
    }
}
