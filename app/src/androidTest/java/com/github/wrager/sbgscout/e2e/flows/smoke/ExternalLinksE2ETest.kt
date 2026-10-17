package com.github.wrager.sbgscout.e2e.flows.smoke

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Внешние ссылки со страницы игры
 * ([com.github.wrager.sbgscout.webview.SbgWebViewClient.shouldOverrideUrlLoading]):
 * ссылки приложений (оплата через СБП ведёт на `bankXXX://`) передаются системе,
 * магазин игры открывается в браузере, а WebView остаётся на игре.
 *
 * Игра открывает внешние ссылки через `location.href` в том же окне
 * (`confirmOuter` в refs/game/script.js), поэтому и здесь переход начинается
 * со страницы: для `loadUrl` из приложения `shouldOverrideUrlLoading` не вызывается.
 * Исходящие intent'ы перехватывает Espresso-Intents, чтобы эмулятор не запускал
 * браузер и приложения банков.
 */
@RunWith(AndroidJUnit4::class)
class ExternalLinksE2ETest : E2ETestBase() {

    private lateinit var scenario: ActivityScenario<GameActivity>

    @Test
    fun bankAppLink_opensExternallyAndGameStays() {
        val game = launchGame()

        Intents.init()
        try {
            stubExternalActivities()

            game.evaluateJs("location.href = '$BANK_APP_URL'")

            awaitIntended(allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse(BANK_APP_URL))))
            assertGameStays(game)
        } finally {
            Intents.release()
        }
    }

    @Test
    fun storeLink_opensInBrowserAndGameStays() {
        val game = launchGame()
        val storeUrl = "${server.baseUrl}/store/"

        Intents.init()
        try {
            stubExternalActivities()

            // Как кнопка «Магазин» в настройках игры: data-href="/store/".
            game.evaluateJs("location.href = '/store/'")

            awaitIntended(
                allOf(
                    hasAction(Intent.ACTION_VIEW),
                    hasData(Uri.parse(storeUrl)),
                    hasBrowserSelector(),
                ),
            )
            assertGameStays(game)
            val storeRequests = recordedRequests().filter { it.startsWith("GET /store") }
            assertTrue("Магазин не должен грузиться в WebView: $storeRequests", storeRequests.isEmpty())
        } finally {
            Intents.release()
        }
    }

    private fun launchGame(): GameScreen {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        scenario = launchGameActivity()
        return GameScreen(scenario, idling).waitForLoaded()
    }

    /**
     * WebView осталась на игре: та же страница, и в истории нет новых записей —
     * ни загруженной ссылки, ни страницы ошибки.
     */
    private fun assertGameStays(game: GameScreen) {
        assertEquals("\"/app\"", game.evaluateJs("location.pathname"))
        var historySize = 0
        scenario.onActivity { historySize = it.webView.copyBackForwardList().size }
        assertEquals("В истории WebView не должно появиться новых записей", 1, historySize)
    }

    /** Ни браузер, ни приложение банка на эмуляторе не запускаются. */
    private fun stubExternalActivities() {
        Intents.intending(anyIntent())
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    /**
     * Переход со страницы асинхронный: intent уходит, когда WebView вызовет
     * `shouldOverrideUrlLoading`, поэтому записанные intent'ы опрашиваются
     * до таймаута, а затем проверяются через [Intents.intended].
     */
    private fun awaitIntended(matcher: Matcher<Intent>) {
        val deadline = SystemClock.uptimeMillis() + INTENT_TIMEOUT_MS
        while (Intents.getIntents().none { matcher.matches(it) } && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Intents.intended(matcher)
    }

    /**
     * Selector, по которому система выбирает приложение: ACTION_VIEW + BROWSABLE
     * со ссылкой без хоста. Под него подходят только браузеры, поэтому магазин
     * не возвращается в Scout, который сам принимает ссылки сайта игры.
     */
    private fun hasBrowserSelector(): Matcher<Intent> = object : TypeSafeMatcher<Intent>() {
        override fun describeTo(description: Description) {
            description.appendText(
                "has selector: action=${Intent.ACTION_VIEW}, data=$BROWSER_SELECTOR_URL, " +
                    "category=${Intent.CATEGORY_BROWSABLE}",
            )
        }

        override fun describeMismatchSafely(item: Intent, mismatchDescription: Description) {
            mismatchDescription.appendText("selector was ").appendValue(item.selector)
        }

        override fun matchesSafely(item: Intent): Boolean {
            val selector = item.selector ?: return false
            return selector.action == Intent.ACTION_VIEW &&
                selector.data == Uri.parse(BROWSER_SELECTOR_URL) &&
                selector.hasCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /** Все запросы, пришедшие на fake-сервер, в виде "METHOD /path". */
    private fun recordedRequests(): List<String> =
        generateSequence { server.takeRequest(DRAIN_TIMEOUT_MS) }
            .map { "${it.method} ${it.path}" }
            .toList()

    private companion object {
        const val BANK_APP_URL = "bank100000000004://Main/tpay/payment-1"
        const val BROWSER_SELECTOR_URL = "https://"
        const val INTENT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 100L
        const val DRAIN_TIMEOUT_MS = 500L
    }
}
