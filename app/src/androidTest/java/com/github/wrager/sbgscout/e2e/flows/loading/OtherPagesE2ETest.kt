package com.github.wrager.sbgscout.e2e.flows.loading

import android.content.Intent
import android.net.Uri
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Страницы вне игры (вход, правила): сигнала готовности игры на них не будет,
 * а надпись о загрузке игры на них не показывается.
 * На странице входа видны "Scout" (до входа это единственный путь к настройкам
 * приложения) и "Reload". На других страницах нативные кнопки перекрывали бы
 * контент, поэтому остаётся только "Reload" и только тогда, когда "Назад"
 * не вернёт в игру.
 *
 * Пока не загрузилась страница игры, idling не срабатывает: такие проверки
 * не используют Espresso и ждут страницу по маркеру `window.__sbgFakePage`.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
@RunWith(AndroidJUnit4::class)
class OtherPagesE2ETest : E2ETestBase() {
    @Test
    fun loginPageAfterRedirect_keepsScoutAndReload() {
        // Без session-cookie: /app отвечает 302 на /login, игра не грузится.
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        val game = GameScreen(launchGameActivity(), idling)

        game
            .waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.LOGIN_PAGE)
            .assertLoadingUiStays(LoadingUiState.LOGIN_PAGE)
    }

    @Test
    fun loginPageAfterGame_showsScout_evenAfterHtmlButtonReplacedIt() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-settings-content.html")
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val game = GameScreen(launchGameActivity(), idling).waitForLoaded()
        // В игре нативную «Scout» заменила HTML-кнопка в настройках игры.
        game.waitForLoadingUi(LoadingUiState.hidden(scout = false))

        game.navigateByScript("/login/")
        game
            .waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.LOGIN_PAGE)

        // Закрытие настроек восстанавливает «Scout» по текущей странице.
        game.openSettings().close()
        game
            .waitForLoadingUi(LoadingUiState.LOGIN_PAGE)
            .assertLoadingUiStays(LoadingUiState.LOGIN_PAGE)
    }

    @Test
    fun loginPage_thenGame_keepsScout() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling)
        game
            .waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.LOGIN_PAGE)

        LoginScreen(scenario).waitUntilReady().submitFakeAuth()

        // В фикстуре игры нет .settings-content: «Scout» остаётся, как под CUI.
        game
            .waitForLoaded()
            .waitForFakePage("app")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
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
        game
            .waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = false))

        Espresso.pressBack()
        game
            .waitForFakePage("app")
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
        game.tapElement("fake-rules-link")
        game
            .waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))

        scenario.recreate()

        // Новая Activity стартует с надписью, «Scout» и «Reload» из разметки;
        // восстановленная страница правил должна их скрыть.
        game
            .waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = false))
    }

    @Test
    fun deepLinkToOtherPage_keepsReload_thatOpensGame() {
        // Ссылка открыта из другого приложения при закрытом Scout: истории у WebView нет.
        server.gamePageBody = AssetLoader.read(GAME_WITH_RULES_LINK_FIXTURE)
        server.rulesPageBody = AssetLoader.read(RULES_FIXTURE)
        CookieFixtures.injectFakeAuth(server.baseUrl)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${server.baseUrl}/rules/game/"))
        val game = GameScreen(launchGameActivityWithIntent(intent), idling)

        game
            .waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.OTHER_PAGE_WITHOUT_HISTORY)
            .assertLoadingUiStays(LoadingUiState.OTHER_PAGE_WITHOUT_HISTORY)

        game.pressReload()
        game
            .waitForLoaded()
            .waitForFakePage("app")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
    }

    private companion object {
        const val LOGIN_FIXTURE = "fixtures/login-page-minimal.html"
        const val RULES_FIXTURE = "fixtures/rules-page-minimal.html"
        const val GAME_WITH_RULES_LINK_FIXTURE = "fixtures/app-page-with-rules-link.html"
    }
}
