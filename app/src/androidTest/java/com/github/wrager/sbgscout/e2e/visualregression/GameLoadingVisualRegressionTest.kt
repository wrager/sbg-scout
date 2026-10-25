package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает game-loading-light.png - экран загрузки игры с надписью "Загрузка...",
 * "Настройки SBG Scout" и "Reload" между центром и нижней частью экрана.
 *
 * Фикстура app-page-loading-screen-realistic.html не убирает экран загрузки,
 * поэтому игра остаётся в состоянии загрузки. Индикатора загрузки страницы
 * в снимке нет: он виден, только пока грузятся ресурсы страницы, и анимирован.
 *
 * Только light: фикстура без dark CSS.
 *
 * 🤖 Generated with GPT-6 Sol
 */
@RunWith(AndroidJUnit4::class)
class GameLoadingVisualRegressionTest : E2ETestBase() {
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
    fun captureGameLoadingLight() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-loading-screen-realistic.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        GameScreen(launchGameActivity(), idling)
            .waitForLoaded()
            .waitForLoadingUi(LoadingUiState.LOADING)
            .waitForNativeButtonsInPlace()
            .waitForPageLoadingIndicatorHidden()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        ClipboardOverlay.waitUntilGone()

        VisualRegressionCapture.captureFullNative("game-loading-light")
    }
}
