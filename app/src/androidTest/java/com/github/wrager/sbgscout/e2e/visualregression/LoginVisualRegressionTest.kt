package com.github.wrager.sbgscout.e2e.visualregression

import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
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

        // GameActivity накладывает на login WebView loadingOverlay с
        // "Инициализация игры..." и нативными кнопками Settings/Reload -
        // обычно убираются JS-callback'ами, но на /login страница не
        // вызывает game-bridge'ы. Скрываем вручную.
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.loadingOverlay)?.visibility = View.GONE
            activity.findViewById<View>(R.id.gameInitializingLabel)?.visibility = View.GONE
            activity.findViewById<View>(R.id.reloadButton)?.visibility = View.GONE
            activity.findViewById<View>(R.id.settingsButton)?.visibility = View.GONE
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // restoreClipboard в @After предыдущего теста делает setPrimaryClip;
        // на API 33+ это триггерит system "Copied" toast/snackbar. Toast
        // живёт переменное время (Snackbar.LENGTH_SHORT ~1.5s + анимация
        // dismiss), фиксированный sleep флэйкнул: в одном прогоне успевал
        // исчезнуть, в другом - нет, baseline ловил его, последующие прогоны
        // - нет. Активно ждём пока UiAutomator перестанет видеть текст
        // "Copied" в иерархии view, с верхней границей.
        waitForCopiedSnackbarGone()

        VisualRegressionCapture.captureFullNative("login-light")
    }

    private fun waitForCopiedSnackbarGone() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadline = SystemClock.uptimeMillis() + COPIED_GONE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.text(COPIED_TEXT)) == null) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // Snackbar не исчез за timeout - не блокируем тест, capture может
        // быть нестабильным, host-side compare это поймает.
    }

    private companion object {
        const val COPIED_GONE_TIMEOUT_MS = 6_000L
        const val POLL_INTERVAL_MS = 100L
        const val COPIED_TEXT = "Copied"
    }
}
