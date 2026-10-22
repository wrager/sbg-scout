package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Системная "назад" на странице игры (`GameBackNavigation`). Фикстура повторяет
 * разметку попапов игры и считает события `backbutton` и клики по кнопкам закрытия.
 *
 * 🤖 Generated with Claude Fable 5.1
 */
@RunWith(AndroidJUnit4::class)
class GameBackNavigationE2ETest : E2ETestBase() {
    private lateinit var scenario: ActivityScenario<GameActivity>

    @Test
    fun back_closesOpenPopupWithItsCloseButton() {
        val game = launchGameWithPopups()
        game.evaluateJs("document.getElementById('notifs-menu').click()")
        assertEquals("false", game.evaluateJs(IS_NOTIFS_HIDDEN))

        pressBack()

        awaitJs(game, IS_NOTIFS_HIDDEN, "true")
        assertEquals("1", game.evaluateJs(CLOSE_CLICKS))
        assertEquals("\"[true]\"", game.evaluateJs(BACK_EVENTS))
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun back_hidesPopupWithoutCloseButton() {
        val game = launchGameWithPopups()
        game.evaluateJs("document.querySelector('.levelup').classList.remove('hidden')")

        pressBack()

        awaitJs(game, IS_LEVELUP_HIDDEN, "true")
        assertEquals("0", game.evaluateJs(CLOSE_CLICKS))
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun back_cancelledByPageListener_leavesPopupOpen() {
        val game = launchGameWithPopups()
        game.evaluateJs("window.__sbgCancelBack = true")
        game.evaluateJs("document.getElementById('notifs-menu').click()")

        pressBack()

        awaitJs(game, BACK_EVENTS, "\"[true]\"")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        assertEquals("false", game.evaluateJs(IS_NOTIFS_HIDDEN))
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun back_withoutOpenPopups_finishesActivity() {
        launchGameWithPopups()
        val activity = currentActivity()

        pressBackUnconditionally()

        awaitFinishing(activity)
    }

    @Test
    fun back_afterLogin_finishesInsteadOfReturningToLoginPage() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-popups.html")
        server.loginPageBody = AssetLoader.read("fixtures/login-page-full-screen-button.html")
        scenario = launchGameActivity()
        LoginScreen(scenario).waitUntilReady()
        tapWebView()
        GameScreen(scenario, idling).waitForLoaded()
        drainRequests()
        val activity = currentActivity()
        assertTrue("После входа в истории WebView есть страница входа", activity.webView.canGoBack())

        pressBackUnconditionally()

        awaitFinishing(activity)
        assertNull(
            "WebView не должна возвращаться на страницу входа",
            nextRequest { it.path.orEmpty().startsWith("/login") },
        )
    }

    private fun launchGameWithPopups(): GameScreen {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-popups.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        scenario = launchGameActivity()
        return GameScreen(scenario, idling).waitForLoaded()
    }

    private fun awaitJs(
        game: GameScreen,
        script: String,
        expected: String,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        var actual = game.evaluateJs(script)
        while (actual != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            actual = game.evaluateJs(script)
        }
        assertEquals(script, expected, actual)
    }

    /**
     * Настоящий тап, а не JS-клик: без user activation WebView 101 помечает
     * запись `/login` в истории пропускаемой, и `canGoBack()` ложен. Точка
     * в левой четверти ширины на середине высоты не задета нативными кнопками,
     * которые стоят внизу по центру.
     * 🤖 Generated with Claude Fable 5.1
     */
    private fun tapWebView() {
        val location = IntArray(2)
        var width = 0
        var height = 0
        scenario.onActivity { activity ->
            activity.webView.getLocationOnScreen(location)
            width = activity.webView.width
            height = activity.webView.height
        }
        UiDevice
            .getInstance(InstrumentationRegistry.getInstrumentation())
            .click(location[0] + width / 4, location[1] + height / 2)
    }

    private fun drainRequests() {
        var request = server.takeRequest(REQUEST_TIMEOUT_MS)
        while (request != null) request = server.takeRequest(REQUEST_TIMEOUT_MS)
    }

    private fun nextRequest(predicate: (RecordedRequest) -> Boolean): RecordedRequest? {
        var request = server.takeRequest(REQUEST_TIMEOUT_MS)
        while (request != null && !predicate(request)) request = server.takeRequest(REQUEST_TIMEOUT_MS)
        return request
    }

    private fun currentActivity(): GameActivity {
        lateinit var activity: GameActivity
        scenario.onActivity { activity = it }
        return activity
    }

    /**
     * После `finish()` Activity ещё несколько секунд живёт в CREATED, пока
     * система её не уничтожит, поэтому ждём `isFinishing`, а не DESTROYED.
     * 🤖 Generated with Claude Fable 5.1
     */
    private fun awaitFinishing(activity: GameActivity) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (!isFinishing(activity) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue("Activity должна завершаться по кнопке назад", isFinishing(activity))
    }

    private fun isFinishing(activity: GameActivity): Boolean = activity.isFinishing || activity.isDestroyed

    private companion object {
        const val IS_NOTIFS_HIDDEN = "document.querySelector('.notifs').classList.contains('hidden')"
        const val IS_LEVELUP_HIDDEN = "document.querySelector('.levelup').classList.contains('hidden')"
        const val CLOSE_CLICKS = "window.__sbgCloseClicks"
        const val BACK_EVENTS = "JSON.stringify(window.__sbgBackEvents)"
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
        const val SETTLE_MS = 500L
        const val REQUEST_TIMEOUT_MS = 500L
    }
}
