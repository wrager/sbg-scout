package com.github.wrager.sbgscout.e2e.flows.loading

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Нативная "Reload" на странице игры скрывается только после полной загрузки
 * игры: bootstrap ScoutBridge ждёт, пока игра уберёт свой экран загрузки
 * `.loading-screen`. При ошибке этапа и при `.fatal-error` "Reload" и "Scout"
 * остаются. До загрузки HTML-кнопка Scout в настройки игры не вставляется.
 * Кнопки стоят в левом верхнем углу зеркально индикатору загрузки страницы.
 *
 * Исходы загрузки изображает фикстура `app-page-loading-screen.html`
 * (`window.__sbgFakeLoading`). Отрицательные проверки идут окном
 * [GameScreen.assertLoadingUiStays], много длиннее интервала опроса bootstrap.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
@RunWith(AndroidJUnit4::class)
class GameLoadingE2ETest : E2ETestBase() {
    @Test
    fun pageWithoutLoadingScreen_hidesLoadingUi_andHtmlButtonReplacesScout() {
        // Страница без .loading-screen (как прежние фикстуры) готова сразу.
        val game = launchGame("fixtures/app-page-with-settings-content.html")

        game.waitForLoadingUi(LoadingUiState.hidden(scout = false))
        assertTrue("HTML-кнопка должна быть вставлена", game.isHtmlButtonInjected())
    }

    @Test
    fun pageWithoutSettingsContent_hidesLoadingUi_andKeepsScout() {
        // Как под CUI: .settings-content нет, нативная «Scout» остаётся
        // единственным путём к настройкам.
        val game = launchGame("fixtures/app-page-minimal.html")

        game
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = true))
            .waitForTopButtonsInCorner()
        assertFalse("HTML-кнопке некуда вставляться", game.isHtmlButtonInjected())
    }

    @Test
    fun loadingScreen_keepsLoadingUi_untilGameRemovesIt() {
        val game = launchGame(LOADING_SCREEN_FIXTURE)

        game
            .waitForLoadingUi(LoadingUiState.LOADING)
            .assertLoadingUiStays(LoadingUiState.LOADING)
            .waitForTopButtonsInCorner()
        assertFalse("HTML-кнопка вставлена до загрузки игры", game.isHtmlButtonInjected())

        game.evaluateJs("__sbgFakeLoading.finish()")

        game.waitForLoadingUi(LoadingUiState.hidden(scout = false))
        assertTrue("HTML-кнопка должна быть вставлена", game.isHtmlButtonInjected())
    }

    @Test
    fun failedLoadingStep_keepsLoadingUiWithReload() {
        val game = launchGame(LOADING_SCREEN_FIXTURE)
        game.waitForLoadingUi(LoadingUiState.LOADING)

        game.evaluateJs("__sbgFakeLoading.failStep()")

        // Этап упал, экран загрузки игры остался: остаётся и "Reload".
        assertEquals(
            "true",
            game.evaluateJs(
                "document.querySelector('.loading-screen .loading-screen__task.failure') !== null",
            ),
        )
        game.assertLoadingUiStays(LoadingUiState.LOADING)
        assertFalse("HTML-кнопка вставлена при упавшей загрузке", game.isHtmlButtonInjected())
    }

    @Test
    fun fatalError_keepsReloadAndScoutInCorner() {
        val game = launchGame(LOADING_SCREEN_FIXTURE)
        game.waitForLoadingUi(LoadingUiState.LOADING)

        game.evaluateJs("__sbgFakeLoading.fatalError()")

        // Экран загрузки пропал вместе со страницей, но игра не загрузилась.
        assertEquals(
            "true",
            game.evaluateJs(
                "document.querySelector('.loading-screen') === null && " +
                    "document.querySelector('.fatal-error') !== null",
            ),
        )
        game
            .waitForLoadingUi(LoadingUiState.LOADING)
            .assertLoadingUiStays(LoadingUiState.LOADING)
            .waitForTopButtonsInCorner()
        assertFalse("HTML-кнопка вставлена при фатальной ошибке", game.isHtmlButtonInjected())
    }

    private fun launchGame(fixture: String): GameScreen {
        server.gamePageBody = AssetLoader.read(fixture)
        CookieFixtures.injectFakeAuth(server.baseUrl)
        return GameScreen(launchGameActivity(), idling).waitForLoaded()
    }

    private companion object {
        const val LOADING_SCREEN_FIXTURE = "fixtures/app-page-loading-screen.html"
    }
}
