package com.github.wrager.sbgscout.e2e.screens

import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.WebViewIdlingResource
import com.github.wrager.sbgscout.settings.SettingsFragment
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

    /**
     * Открывает settings overlay кликом по большой кнопке «⚙ Scout» в верхней
     * панели. Кнопка остаётся видимой на fake-странице, потому что ScoutBridge
     * bootstrap не находит `.settings-content` и не вызывает `onHtmlButtonInjected`.
     *
     * После клика GameActivity делает `findViewById(R.id.settingsContainer).visibility
     * = VISIBLE` — в контейнере уже сидит SettingsFragment, прикреплённый в onCreate.
     * Возвращает [SettingsOverlayScreen] для цепочечных вызовов.
     */
    fun openSettings(): SettingsOverlayScreen {
        onView(withId(R.id.settingsButton)).perform(click())
        // Дождаться, пока SettingsFragment будет attached и PreferenceFragmentCompat
        // закончит inflate — без этого onView(withText(...)) не найдёт preference.
        waitUntilSettingsFragmentReady()
        return SettingsOverlayScreen(scenario)
    }

    /**
     * Возвращает WebView из GameActivity. Нужен скриншот-тестам для прямой
     * работы с DOM (запросы bounding rects, invalidate перед takeScreenshot).
     */
    fun webView(): WebView {
        var wv: WebView? = null
        scenario.onActivity { wv = it.webView }
        return wv ?: error("WebView не найден в GameActivity")
    }

    /**
     * Скрывает loadingOverlay и нативные кнопки поверх WebView, чтобы они
     * не попали на скриншот. На обычном flow эти элементы убираются bootstrap-
     * callback'ами в JS, но JNI-call'и к Activity не всегда успевают до того,
     * как тест берёт screenshot. Дополнительно `invalidate()` принудительно
     * перерисовывает WebView в frame buffer, иначе UiAutomation иногда
     * захватывает кадр ПЕРЕД paint pass'ом WebView.
     */
    fun hideTransientChromeForScreenshot() {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.loadingOverlay)?.visibility = View.GONE
            activity.findViewById<View>(R.id.gameInitializingLabel)?.visibility = View.GONE
            activity.findViewById<View>(R.id.reloadButton)?.visibility = View.GONE
            activity.findViewById<View>(R.id.settingsButton)?.visibility = View.GONE
            activity.webView.invalidate()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitUntilSettingsFragmentReady() {
        val deadline = System.currentTimeMillis() + SETTINGS_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.settingsContainer)
                ready = fragment is SettingsFragment && fragment.isResumed
            }
            if (ready) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("SettingsFragment не стал RESUMED за ${SETTINGS_READY_TIMEOUT_MS}ms")
    }

    companion object {
        private const val EVAL_TIMEOUT_MS = 5_000L
        private const val SETTINGS_READY_TIMEOUT_MS = 3_000L
        private const val POLL_INTERVAL_MS = 50L
    }
}
