package com.github.wrager.sbgscout.e2e.flows.smoke

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Магазин остаётся в WebView с текущей сессией, Робокасса открывается в Custom Tab,
 * а ссылки приложений (например, bankXXX://) передаются системе.
 *
 * Как в confirmOuter из refs/game/script.js, переход начинается со страницы:
 * для loadUrl из приложения shouldOverrideUrlLoading не вызывается.
 * Espresso-Intents перехватывает внешние запуски: настоящей оплаты и сетевых
 * обращений к Робокассе или игре в этих тестах нет.
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
            assertPageAndHistory(game, "/app", 1)
        } finally {
            Intents.release()
        }
    }

    @Test
    fun storeLink_keepsSessionInWebViewAndBackReturnsToGame() {
        val game = launchGame()

        Intents.init()
        try {
            stubExternalActivities()
            openStore(game)

            assertTrue("Магазин не должен запускать внешние Activity", Intents.getIntents().isEmpty())
            assertPageAndHistory(game, "/store/", 2)

            scenario.onActivity {
                assertTrue("Магазин должен иметь предыдущую страницу", it.webView.canGoBack())
            }
            pressBack()
            awaitPage(game, "/app")
            assertEquals("\"app\"", game.evaluateJs("window.__sbgFakePage"))
        } finally {
            Intents.release()
        }
    }

    @Test
    fun storePayment_opensCustomTabAndPreservesStore() {
        val game = launchGame()
        openStore(game)
        game.evaluateJs("document.getElementById('order-note').value = 'my order'")

        Intents.init()
        try {
            stubExternalActivities()
            game.evaluateJs("document.getElementById('payment-link').click()")

            val paymentIntent =
                allOf(
                    hasAction(Intent.ACTION_VIEW),
                    hasData(Uri.parse(PAYMENT_URL)),
                    hasExtraWithKey(CustomTabsIntent.EXTRA_SESSION),
                )
            awaitIntended(paymentIntent)
            val launched = Intents.getIntents().single { paymentIntent.matches(it) }
            assertEquals(0, launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals(
                CustomTabsIntent.SHOW_PAGE_TITLE,
                launched.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1),
            )

            // Внешняя Activity уже вернула RESULT_OK: магазин и его форма сохранены.
            assertPageAndHistory(game, "/store/", 2)
            assertEquals("\"my order\"", game.evaluateJs("document.getElementById('order-note').value"))
        } finally {
            Intents.release()
        }
    }

    private fun launchGame(): GameScreen {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-store-link.html")
        server.storePageBody = AssetLoader.read("fixtures/store-page.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        scenario = launchGameActivity()
        return GameScreen(scenario, idling).waitForLoaded()
    }

    private fun openStore(game: GameScreen) {
        // Настоящий тап нужен для user activation: иначе WebView 101 помечает
        // предыдущую запись истории как пропускаемую и canGoBack() возвращает false.
        // CENTER_LEFT даёт x=0: WebView может пропустить касание на границе.
        // Тапаем на четверти ширины, внутри ссылки и в стороне от нативных
        // кнопок в правом верхнем углу.
        onView(withId(R.id.gameWebView)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                GeneralLocation.translate(GeneralLocation.CENTER, -0.25f, 0f),
                Press.FINGER,
            ),
        )
        val request = server.takeRequestMatching { it.method == "GET" && it.path == "/store/" }
        assertTrue(
            "Магазин должен получить текущую сессию WebView",
            request.getHeader("Cookie").orEmpty().contains("hittoken=${CookieFixtures.FAKE_SESSION_TOKEN}"),
        )
        awaitPage(game, "/store/")
        assertEquals("\"store\"", game.evaluateJs("window.__sbgFakePage"))
    }

    private fun awaitPage(
        game: GameScreen,
        path: String,
    ) {
        val ready = "location.pathname === '$path' && document.readyState === 'complete'"
        val deadline = SystemClock.uptimeMillis() + NAVIGATION_TIMEOUT_MS
        while (game.evaluateJs(ready) != "true" && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertEquals("Страница $path должна загрузиться", "true", game.evaluateJs(ready))
    }

    private fun assertPageAndHistory(
        game: GameScreen,
        path: String,
        expectedHistorySize: Int,
    ) {
        assertEquals("\"$path\"", game.evaluateJs("location.pathname"))
        var historySize = 0
        scenario.onActivity { historySize = it.webView.copyBackForwardList().size }
        assertEquals("В истории WebView не должно быть оплаты или ошибки", expectedHistorySize, historySize)
    }

    private fun stubExternalActivities() {
        Intents
            .intending(anyIntent())
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    private fun awaitIntended(matcher: Matcher<Intent>) {
        val deadline = SystemClock.uptimeMillis() + NAVIGATION_TIMEOUT_MS
        while (Intents.getIntents().none { matcher.matches(it) } && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Intents.intended(matcher)
    }

    private companion object {
        const val BANK_APP_URL = "bank100000000004://Main/tpay/payment-1"
        const val PAYMENT_URL =
            "https://auth.robokassa.ru/Merchant/Index.aspx?MerchantLogin=sbg_game&InvoiceID=1"
        const val NAVIGATION_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
