package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.View
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import androidx.test.platform.app.InstrumentationRegistry
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

        // saveClipboard в E2ETestBase@Before читает primary clip; на API 33+
        // это триггерит system "Copied" toast/snackbar. Toast живёт ~2s -
        // ждём, пока он исчезнет, иначе попадёт на baseline в одном
        // прогоне и не попадёт в другом.
        Thread.sleep(CLIPBOARD_TOAST_TIMEOUT_MS)

        VisualRegressionCapture.captureFullNative("login-light")
    }

    private companion object {
        const val CLIPBOARD_TOAST_TIMEOUT_MS = 3_000L
    }
}
