package com.github.wrager.sbgscout.e2e.flows.loading

import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Страницы вне игры (вход, правила): сигнала готовности игры на них не будет,
 * поэтому подложка, «Инициализация игры…» и «Reload» там скрыты. Нативная
 * «Scout» остаётся только на странице входа — до входа это единственный путь
 * к настройкам приложения; на других страницах она перекрывала бы контент.
 *
 * Пока не загрузилась страница игры, idling не срабатывает: такие проверки
 * не используют Espresso и ждут страницу по маркеру `window.__sbgFakePage`.
 */
@RunWith(AndroidJUnit4::class)
class OtherPagesE2ETest : E2ETestBase() {

    @Test
    fun loginPageAfterRedirect_hidesLoadingUi_andKeepsScout() {
        // Без session-cookie: /app отвечает 302 на /login, игра не грузится.
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        val game = GameScreen(launchGameActivity(), idling)

        game.waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = true))
    }

    @Test
    fun loginPageAfterGame_showsScout_evenAfterHtmlButtonReplacedIt() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-settings-content.html")
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        server.rulesPageBody = AssetLoader.read(RULES_FIXTURE)
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val game = GameScreen(launchGameActivity(), idling).waitForLoaded()
        // В игре нативную «Scout» заменила HTML-кнопка в настройках игры.
        game.waitForLoadingUi(LoadingUiState.hidden(scout = false))

        game.navigateByScript("/login/")
        game.waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))

        // Закрытие настроек восстанавливает «Scout» по текущей странице.
        game.openSettings().close()
        game.waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = true))

        game.navigateByScript("/rules/game/")
        game.waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
    }

    @Test
    fun rulesPage_hidesScout_andBackToGameShowsItAgain() {
        // В фикстуре игры нет .settings-content: «Scout» в игре остаётся, как под CUI.
        server.gamePageBody = AssetLoader.read(GAME_WITH_RULES_LINK_FIXTURE)
        server.rulesPageBody = AssetLoader.read(RULES_FIXTURE)
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val game = GameScreen(launchGameActivity(), idling).waitForLoaded()
        game.waitForLoadingUi(LoadingUiState.hidden(scout = true))

        // Настоящее касание: страница игры остаётся в истории для «Назад».
        game.tapElement("fake-rules-link")
        game.waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = false))

        Espresso.pressBack()
        game.waitForFakePage("app")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))

        // В игре «Scout» снова разрешена: закрытие настроек её не прячет.
        game.openSettings().close()
        game.waitForLoadingUi(LoadingUiState.hidden(scout = true))
    }

    @Test
    fun recreatedActivityOnRulesPage_keepsLoadingUiAndScoutHidden() {
        // Как после выгрузки Scout в фоне: Activity создаётся заново, а WebView
        // восстанавливает не игру, а страницу правил.
        server.gamePageBody = AssetLoader.read(GAME_WITH_RULES_LINK_FIXTURE)
        server.rulesPageBody = AssetLoader.read(RULES_FIXTURE)
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        game.navigateByScript("/rules/game/")
        game.waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))

        scenario.recreate()

        // Новая Activity стартует с надписью, «Scout» и «Reload» из разметки;
        // восстановленная страница правил должна их скрыть.
        game.waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = false))
    }

    private companion object {
        const val LOGIN_FIXTURE = "fixtures/login-page-minimal.html"
        const val RULES_FIXTURE = "fixtures/rules-page-minimal.html"
        const val GAME_WITH_RULES_LINK_FIXTURE = "fixtures/app-page-with-rules-link.html"
    }
}
