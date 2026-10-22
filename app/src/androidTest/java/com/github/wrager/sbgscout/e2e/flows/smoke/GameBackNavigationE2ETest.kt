package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
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

        pressBackUnconditionally()

        awaitActivityDestroyed()
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

    private fun awaitActivityDestroyed() {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertEquals("Activity должна завершиться по кнопке назад", Lifecycle.State.DESTROYED, scenario.state)
    }

    private companion object {
        const val IS_NOTIFS_HIDDEN = "document.querySelector('.notifs').classList.contains('hidden')"
        const val IS_LEVELUP_HIDDEN = "document.querySelector('.levelup').classList.contains('hidden')"
        const val CLOSE_CLICKS = "window.__sbgCloseClicks"
        const val BACK_EVENTS = "JSON.stringify(window.__sbgBackEvents)"
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
        const val SETTLE_MS = 500L
    }
}
