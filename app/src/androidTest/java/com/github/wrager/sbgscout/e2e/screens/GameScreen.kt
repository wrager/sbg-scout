package com.github.wrager.sbgscout.e2e.screens

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.e2e.infra.WebViewIdlingResource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue

/**
 * PageObject для [GameActivity].
 *
 * Оборачивает [ActivityScenario] и [WebViewIdlingResource]: ждёт загрузки
 * игровой страницы через idling, выполняет JS через `evaluateJavascript`
 * синхронно с помощью [CountDownLatch].
 */
class GameScreen(
    private val scenario: ActivityScenario<GameActivity>,
    private val idlingResource: WebViewIdlingResource,
) : Screen {

    override fun assertDisplayed() {
        scenario.onActivity { activity ->
            // Доступ к полю уже гарантирует инициализацию: lateinit бросит
            // UninitializedPropertyAccessException, если setupWebView ещё не отработал.
            assertTrue("WebView must be attached", activity.webView.parent != null)
        }
    }

    fun waitForLoaded(): GameScreen {
        // Espresso.onIdle() возвращается, когда все зарегистрированные
        // IdlingResource сообщили idle. WebViewIdlingResource.markLoaded
        // триггерится из callback onGamePageFinished.
        Espresso.onIdle()
        return this
    }

    fun evaluateJs(script: String, timeout: Long = EVAL_TIMEOUT_MS): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        scenario.onActivity { activity ->
            activity.webView.evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        assertTrue(
            "evaluateJavascript не завершился за ${timeout}ms: $script",
            latch.await(timeout, TimeUnit.MILLISECONDS),
        )
        return result.get() ?: "null"
    }

    companion object {
        private const val EVAL_TIMEOUT_MS = 5_000L
    }
}
