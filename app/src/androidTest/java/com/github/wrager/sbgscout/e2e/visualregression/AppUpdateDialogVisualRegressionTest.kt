package com.github.wrager.sbgscout.e2e.visualregression

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает dialog-app-update-light.png и dialog-app-update-dark.png - 3-фазный
 * диалог проверки обновления приложения в финальной фазе UpdateAvailable
 * (progress-indicator скрыт, показан release-notes текст и кнопка Download).
 *
 * GitHub-stub фиксированный (`999.0.0` + `Lots of new features`), поэтому
 * содержимое диалога детерминировано. Ждём фазу UpdateAvailable через
 * waitForDialogTitle - до неё progressIndicator анимирован (indeterminate)
 * и pixel-diff невозможен.
 *
 * Capture делается через captureFullNative: затемнённый фон Activity и
 * dim-overlay вокруг диалога - часть UI и должны быть в baseline.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateDialogVisualRegressionTest : E2ETestBase() {
    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
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
    fun captureAppUpdateDialogLight() {
        ThemeOverride.forceLightTheme()
        captureUpdateAvailableDialog("dialog-app-update-light")
    }

    @Test
    @VisualRegressionTest
    fun captureAppUpdateDialogDark() {
        ThemeOverride.forceDarkTheme()
        captureUpdateAvailableDialog("dialog-app-update-dark")
    }

    private fun captureUpdateAvailableDialog(baselineName: String) {
        server.stubGithubReleasesList(
            "wrager",
            "sbg-scout",
            GithubReleaseFixtures.appUpdateReleasesJson(
                version = STUB_VERSION,
                body = STUB_BODY,
            ),
        )
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        GameScreen(scenario, idling)
            .waitForLoaded()
            .openSettings()
            .clickPreferenceByKey(SettingsOverlayScreen.KEY_CHECK_APP_UPDATE)

        val availableTitle = targetContext.getString(R.string.app_update_available)
        waitForDialogTitle(availableTitle)
        // showOnShowListener'у нужно одно дополнительное смешение Choreographer
        // после смены title, чтобы layout пересчитал высоту release-notes
        // TextView и dim-overlay стабилизировался. Без этого первые frame'ы
        // могут поймать промежуточную высоту и pixel-diff между запусками
        // ловит мельчайшие смещения.
        Thread.sleep(DIALOG_SETTLE_MS)

        VisualRegressionCapture.captureFullNative(baselineName)
    }

    /**
     * Ждёт появления View с заданным текстом в корне диалога. Title диалога
     * меняется асинхронно через `dialog.setTitle(...)` после ответа от
     * fake-сервера; ранний capture поймал бы фазу `app_update_checking` с
     * indeterminate progress, который недетерминирован.
     */
    private fun waitForDialogTitle(text: String) {
        val deadline = SystemClock.uptimeMillis() + TITLE_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()))
                return
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        throw AssertionError("Диалог с title='$text' не появился за ${TITLE_WAIT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val STUB_VERSION = "999.0.0"
        const val STUB_BODY = "Lots of new features"
        const val TITLE_WAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
        const val DIALOG_SETTLE_MS = 500L
    }
}
