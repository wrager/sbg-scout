package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает login-light.png - страницу авторизации в light-теме.
 *
 * Login - WebView с fake-фикстурой login-page-minimal.html (без сети, без
 * CSRF, стабильный). Реальный refs/game/login.html не используется: содержит
 * yandex-metrika, i18next-сеть, динамические OG-теги, CSRF-токен - не
 * детерминирован для VR.
 *
 * Только light: фикстура без dark CSS, dark-вариант потребовал бы отдельной
 * dark-фикстуры с инлайн dark-CSS.
 *
 * Без injectFakeAuth: GameActivity редиректит неавторизованного пользователя
 * на /login, что и нужно для VR.
 */
@RunWith(AndroidJUnit4::class)
class LoginVisualRegressionTest : E2ETestBase() {
    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
        ThemeOverride.forceLightTheme()
        StatusBarDemoMode.enable()
    }

    @After
    fun tearDownVisualRegression() {
        StatusBarDemoMode.disable()
        ThemeOverride.resetTheme()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @VisualRegressionTest
    fun captureLoginLight() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        server.loginPageBody = AssetLoader.read("fixtures/login-page-minimal.html")
        // Cookies НЕ инжектируем: сценарий "первый вход", GameActivity
        // редиректит на /login.

        val scenario = launchGameActivity()
        LoginScreen(scenario).waitUntilReady()

        // "Scout" и "Reload" внизу экрана - часть экрана входа и входят в снимок.
        GameScreen(scenario, idling).waitForLoadingUi(LoadingUiState.LOGIN_PAGE)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // restoreClipboard предыдущего теста может оставить панель SystemUI.
        // Её текст зависит от содержимого clipboard и системной локали,
        // поэтому ждём исчезновения preview по resource ID. При timeout
        // завершаем тест до capture, чтобы не сохранять постороннюю панель.
        waitForClipboardOverlayGone()

        VisualRegressionCapture.captureFullNative("login-light")
    }

    private fun waitForClipboardOverlayGone() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val preview = By.res("com.android.systemui", "clipboard_preview")
        assertTrue(
            "Панель буфера обмена SystemUI не исчезла за ${CLIPBOARD_GONE_TIMEOUT_MS}ms",
            device.wait(Until.gone(preview), CLIPBOARD_GONE_TIMEOUT_MS),
        )
    }

    private companion object {
        const val CLIPBOARD_GONE_TIMEOUT_MS = 10_000L
    }
}
