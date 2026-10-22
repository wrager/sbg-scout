package com.github.wrager.sbgscout.e2e.screens

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.WebViewIdlingResource
import com.github.wrager.sbgscout.settings.SettingsFragment
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    fun evaluateJs(
        script: String,
        timeout: Long = EVAL_TIMEOUT_MS,
    ): String {
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
     * панели. Кнопка остаётся видимой на fake-странице игры, потому что ScoutBridge
     * bootstrap не находит `.settings-content` и не вызывает `onHtmlButtonInjected`,
     * а также на странице входа.
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
     * Текущая видимость надписи и нативных кнопок с учётом родителей ([View.isShown]):
     * так, как её видит пользователь.
     */
    fun loadingUiState(): LoadingUiState {
        var state: LoadingUiState? = null
        scenario.onActivity { activity ->
            state =
                LoadingUiState(
                    label = activity.findViewById<View>(R.id.gameInitializingLabel).isShown,
                    reload = activity.findViewById<View>(R.id.reloadButton).isShown,
                    scout = activity.findViewById<View>(R.id.settingsButton).isShown,
                )
        }
        return checkNotNull(state) { "GameActivity недоступна" }
    }

    /**
     * Ждёт видимости надписи и нативных кнопок [expected]: колбэки моста и
     * WebViewClient доходят до UI-потока асинхронно.
     */
    fun waitForLoadingUi(
        expected: LoadingUiState,
        timeoutMs: Long = LOADING_UI_TIMEOUT_MS,
    ): GameScreen {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var actual = loadingUiState()
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            actual = loadingUiState()
        }
        assertEquals("Надпись и нативные кнопки за ${timeoutMs}ms", expected, actual)
        return this
    }

    /**
     * Проверяет, что надпись и нативные кнопки держатся в состоянии [expected] всё
     * окно [durationMs]. Окно много длиннее интервала опроса bootstrap-скрипта
     * ScoutBridge (100 мс): сработай сигнал готовности, изменение успело бы прийти.
     */
    fun assertLoadingUiStays(
        expected: LoadingUiState,
        durationMs: Long = STABLE_WINDOW_MS,
    ): GameScreen {
        val deadline = SystemClock.uptimeMillis() + durationMs
        while (true) {
            assertEquals("Надпись и нативные кнопки в окне ${durationMs}ms", expected, loadingUiState())
            if (SystemClock.uptimeMillis() >= deadline) return this
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * Ждёт, пока индикатор загрузки страницы появится в правом нижнем углу
     * выше навигационной панели и выреза экрана. Страница должна ещё грузиться.
     */
    fun waitForPageLoadingIndicatorInPlace(timeoutMs: Long = LOADING_UI_TIMEOUT_MS): GameScreen =
        waitForPlacement(
            R.id.pageLoadingIndicator,
            R.dimen.page_loading_indicator_margin,
            "Индикатор загрузки страницы",
            timeoutMs,
        ) { it.isAboveNavigationBar && it.isAtEnd }

    /** Ждёт, пока блок нативных кнопок встанет внизу по центру выше навигационной панели и выреза экрана. */
    fun waitForNativeButtonsInPlace(timeoutMs: Long = LOADING_UI_TIMEOUT_MS): GameScreen =
        waitForPlacement(
            R.id.nativeButtonsContainer,
            R.dimen.native_buttons_bottom_margin,
            "Блок нативных кнопок",
            timeoutMs,
        ) { it.isAboveNavigationBar && it.isCentered }

    /** Ждёт полноэкранного режима, в котором у `rootLayout` нет отступа снизу. */
    fun waitForFullscreen(timeoutMs: Long = LOADING_UI_TIMEOUT_MS): GameScreen {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var fullscreen = isFullscreenWithoutBottomPadding()
        while (!fullscreen && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            fullscreen = isFullscreenWithoutBottomPadding()
        }
        assertTrue("Полноэкранный режим без отступа rootLayout снизу за ${timeoutMs}ms", fullscreen)
        return this
    }

    private fun isFullscreenWithoutBottomPadding(): Boolean {
        var fullscreen = false
        scenario.onActivity {
            fullscreen = it.isFullscreen && it.findViewById<View>(R.id.rootLayout).paddingBottom == 0
        }
        return fullscreen
    }

    private fun waitForPlacement(
        viewId: Int,
        marginId: Int,
        name: String,
        timeoutMs: Long,
        inPlace: (ViewPlacement) -> Boolean,
    ): GameScreen {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var placement = viewPlacement(viewId, marginId)
        while (!inPlace(placement) && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            placement = viewPlacement(viewId, marginId)
        }
        assertTrue("$name не на своём месте за ${timeoutMs}ms: $placement", inPlace(placement))
        return this
    }

    private fun viewPlacement(
        viewId: Int,
        marginId: Int,
    ): ViewPlacement {
        var placement: ViewPlacement? = null
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.rootLayout)
            val view = activity.findViewById<View>(viewId)
            val rootLocation = IntArray(2).also { root.getLocationOnScreen(it) }
            val viewLocation = IntArray(2).also { view.getLocationOnScreen(it) }
            val safeBottomInset =
                ViewCompat
                    .getRootWindowInsets(root)
                    ?.getInsetsIgnoringVisibility(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                    )?.bottom ?: 0
            placement =
                ViewPlacement(
                    shown = view.isShown,
                    left = viewLocation[0],
                    right = viewLocation[0] + view.width,
                    bottom = viewLocation[1] + view.height,
                    contentLeft = rootLocation[0] + root.paddingLeft,
                    contentRight = rootLocation[0] + root.width - root.paddingRight,
                    safeBottom = rootLocation[1] + root.height - safeBottomInset,
                    margin = activity.resources.getDimensionPixelSize(marginId),
                )
        }
        return checkNotNull(placement) { "GameActivity недоступна" }
    }

    /** Вставил ли bootstrap ScoutBridge HTML-кнопку в `.settings-content` игры. */
    fun isHtmlButtonInjected(): Boolean =
        evaluateJs("document.getElementById('sbg-scout-settings-btn') !== null") == "true"

    /**
     * Ждёт fake-страницу с маркером `window.__sbgFakePage` = [name] (`app`,
     * `login`, `rules`). В отличие от [waitForLoaded] работает и вне игры:
     * idling срабатывает только на загрузке страницы игры.
     */
    fun waitForFakePage(
        name: String,
        timeoutMs: Long = PAGE_TIMEOUT_MS,
    ): GameScreen {
        val expected = "\"$name\""
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var actual = evaluateJs(FAKE_PAGE_MARKER_SCRIPT)
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            actual = evaluateJs(FAKE_PAGE_MARKER_SCRIPT)
        }
        assertEquals("Fake-страница за ${timeoutMs}ms", expected, actual)
        return this
    }

    /**
     * Переход скриптом страницы (`location.href`) — без действия пользователя.
     * Для проверки «Назад» не подходит: Chromium пропускает при «Назад» запись
     * истории страницы, которую покинули без действия пользователя (history
     * manipulation intervention). Там нужен [tapElement].
     */
    fun navigateByScript(path: String) {
        scenario.onActivity { activity ->
            activity.webView.evaluateJavascript("location.href = '$path'", null)
        }
    }

    /**
     * Касание элемента страницы с id [elementId] настоящим касанием экрана
     * (Espresso в экранных координатах), а не JS `click()`: переход по такому
     * касанию идёт с действием пользователя и остаётся в истории для «Назад».
     * Элемент не должен быть под нативными кнопками внизу экрана.
     */
    fun tapElement(elementId: String) {
        val raw =
            evaluateJs(
                "(function(){var e=document.getElementById('$elementId');if(!e)return null;" +
                    "var r=e.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2];})()",
            )
        check(raw != "null") { "Элемент #$elementId не найден на странице" }
        val cssCenter = JSONArray(raw)
        val screenPoint = FloatArray(2)
        scenario.onActivity { activity ->
            // Без viewport-масштабирования CSS-пиксель WebView равен dp.
            val location = IntArray(2)
            activity.webView.getLocationOnScreen(location)
            val density = activity.resources.displayMetrics.density
            screenPoint[0] = location[0] + cssCenter.getDouble(0).toFloat() * density
            screenPoint[1] = location[1] + cssCenter.getDouble(1).toFloat() * density
        }
        onView(withId(R.id.gameWebView)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                CoordinatesProvider { screenPoint },
                Press.FINGER,
                InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.BUTTON_PRIMARY,
            ),
        )
    }

    /**
     * Нажимает нативную "Reload" без Espresso: вне игры idling не срабатывает,
     * и Espresso ждал бы его до таймаута.
     */
    fun pressReload() {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.reloadButton).performClick()
        }
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
     * Скрывает нативные кнопки поверх WebView, чтобы они не попали на скриншот.
     * На обычном flow они убираются bootstrap-callback'ами в JS, но JNI-call'и
     * к Activity не всегда успевают до того, как тест берёт screenshot.
     * Дополнительно `invalidate()` принудительно перерисовывает WebView в frame
     * buffer, иначе UiAutomation иногда захватывает кадр ПЕРЕД paint pass'ом WebView.
     */
    fun hideTransientChromeForScreenshot() {
        scenario.onActivity { activity ->
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
                val fragment =
                    activity.supportFragmentManager
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
        private const val LOADING_UI_TIMEOUT_MS = 5_000L
        private const val STABLE_WINDOW_MS = 1_500L
        private const val PAGE_TIMEOUT_MS = 5_000L
        private const val FAKE_PAGE_MARKER_SCRIPT = "window.__sbgFakePage"
    }
}
