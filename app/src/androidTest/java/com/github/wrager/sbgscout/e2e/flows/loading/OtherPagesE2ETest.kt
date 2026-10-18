package com.github.wrager.sbgscout.e2e.flows.loading

import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.LoadingUiState
import com.github.wrager.sbgscout.e2e.screens.LoginScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Страницы вне игры (вход, правила): сигнала готовности игры на них не будет,
 * поэтому подложка, «Инициализация игры…» и «Reload» там скрыты. Нативная
 * «Scout» остаётся только на странице входа — до входа это единственный путь
 * к настройкам приложения; на других страницах она перекрывала бы контент.
 * На странице входа блок кнопок прижат к верхнему краю: по центру «Scout»
 * закрывала бы кнопку входа через Telegram. На остальных страницах — по центру.
 *
 * Пока не загрузилась страница игры, idling не срабатывает: такие проверки
 * не используют Espresso и ждут страницу по маркеру `window.__sbgFakePage`.
 */
@RunWith(AndroidJUnit4::class)
class OtherPagesE2ETest : E2ETestBase() {
    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loginPageAfterRedirect_hidesLoadingUi_andKeepsScout() {
        // Без session-cookie: /app отвечает 302 на /login, игра не грузится.
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        val game = GameScreen(launchGameActivity(), idling)

        game
            .waitForFakePage("login")
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
        game
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
            .waitForTopButtonsCentered()

        game.navigateByScript("/login/")
        game
            .waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .waitForTopButtonsAtTop()

        // Закрытие настроек восстанавливает «Scout» по текущей странице.
        game.openSettings().close()
        game
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .assertLoadingUiStays(LoadingUiState.hidden(scout = true))
            .waitForTopButtonsAtTop()

        game.navigateByScript("/rules/game/")
        game
            .waitForFakePage("rules")
            .waitForLoadingUi(LoadingUiState.hidden(scout = false))
            .waitForTopButtonsCentered()
    }

    @Test
    fun loginPage_pinsButtonsToTop_andGameCentersThemAgain() {
        // По центру «Scout» закрывала бы кнопку входа через Telegram посередине страницы.
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling)
        game
            .waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .waitForTopButtonsAtTop()

        LoginScreen(scenario).waitUntilReady().submitFakeAuth()

        game
            .waitForLoaded()
            .waitForFakePage("app")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .waitForTopButtonsCentered()
    }

    @Test
    fun loginPageInFullscreen_keepsButtonsBelowStatusBarArea() {
        // В полноэкранном режиме у rootLayout нет отступа сверху, а статус-бар
        // выезжает по свайпу поверх контента: блок всё равно встаёт ниже его зоны.
        PreferenceManager
            .getDefaultSharedPreferences(targetContext)
            .edit()
            .putBoolean(KEY_FULLSCREEN, true)
            .commit()
        server.loginPageBody = AssetLoader.read(LOGIN_FIXTURE)
        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling)

        game
            .waitForFakePage("login")
            .waitForLoadingUi(LoadingUiState.hidden(scout = true))
            .waitForTopButtonsAtTop()
        scenario.onActivity { activity ->
            assertTrue("Ожидался полноэкранный режим", activity.isFullscreen)
            assertEquals(
                "В полноэкранном режиме у rootLayout нет отступа сверху",
                0,
                activity.findViewById<View>(R.id.rootLayout).paddingTop,
            )
        }
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
        game.navigateByScript("/rules/game/")
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

    private companion object {
        const val KEY_FULLSCREEN = "fullscreen_mode"
        const val LOGIN_FIXTURE = "fixtures/login-page-minimal.html"
        const val RULES_FIXTURE = "fixtures/rules-page-minimal.html"
        const val GAME_WITH_RULES_LINK_FIXTURE = "fixtures/app-page-with-rules-link.html"
    }
}
