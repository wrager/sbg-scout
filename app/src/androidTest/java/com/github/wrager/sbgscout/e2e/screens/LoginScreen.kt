package com.github.wrager.sbgscout.e2e.screens

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import com.github.wrager.sbgscout.GameActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue

/**
 * PageObject для fake-страницы логина.
 *
 * Fake-login — это HTML, который отдаёт FakeGameDispatcher. В тестах
 * мы не кликаем по DOM-кнопкам (это canvas-форма в реальной игре), а дёргаем
 * JS-функцию, которая делает POST на `/login/callback`.
 *
 * Важно: `GameScreen.waitForLoaded` здесь не применим, т.к. его `IdlingResource`
 * триггерится только на странице `/app` (через `GameUrls.isGameAppPage`).
 * На `/login` onGamePageFinished не вызывается, поэтому синхронизация идёт
 * через polling `evaluateJavascript` по маркеру готовности JS-функции.
 */
class LoginScreen(
    private val scenario: ActivityScenario<GameActivity>,
) : Screen {

    override fun assertDisplayed() = Unit

    /**
     * Ждёт, пока на странице появится `submitTelegramStub` — показатель того,
     * что DOM и `<script>` из fixture уже выполнены. Polling: нет другого
     * надёжного сигнала готовности `/login` страницы.
     */
    fun waitUntilReady(timeoutMs: Long = READY_TIMEOUT_MS): LoginScreen {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (evaluateJs("typeof submitTelegramStub === 'function'") == "true") {
                return this
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "submitTelegramStub не появился за ${timeoutMs}ms — страница /login " +
                "не загрузилась или JS фикстуры не исполнился",
        )
    }

    fun submitFakeAuth() {
        scenario.onActivity { activity ->
            activity.webView.evaluateJavascript("submitTelegramStub()") { }
        }
    }

    private fun evaluateJs(script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        scenario.onActivity { activity ->
            activity.webView.evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        assertTrue(
            "evaluateJavascript не завершился за ${EVAL_TIMEOUT_MS}ms: $script",
            latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        return result.get() ?: "null"
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
        const val EVAL_TIMEOUT_MS = 2_000L
    }
}
