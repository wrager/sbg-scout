package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет мост [com.github.wrager.sbgscout.bridge.ScoutBridge]
 * (регистрируется как `__sbg_scout`) и его BOOTSTRAP_SCRIPT, который
 * инжектируется в onPageStarted и после готовности i18next вызывает
 * `onGameReady()` + пытается инжектировать HTML-кнопку настроек в
 * `.settings-content`.
 *
 * На fake-странице (см. `app-page-minimal.html`) `window.i18next` — stub
 * с `isInitialized: true`, поэтому bootstrap делает один check → onReady
 * мгновенно, без setInterval. Проверяем, что все три JS-метода моста
 * доступны и маркер `__sbg_scout_bootstrapped` установлен.
 */
@RunWith(AndroidJUnit4::class)
class ScoutBridgeE2ETest : E2ETestBase() {

    @Test
    fun bootstrap_exposesBridgeMethodsAndMarksBootstrapDone() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        assertEquals("true", game.evaluateJs("typeof __sbg_scout !== 'undefined'"))

        // Три callback'а, которые bootstrap может дёрнуть.
        assertEquals(
            "true",
            game.evaluateJs("typeof __sbg_scout.onGameReady === 'function'"),
        )
        assertEquals(
            "true",
            game.evaluateJs("typeof __sbg_scout.onHtmlButtonInjected === 'function'"),
        )
        assertEquals(
            "true",
            game.evaluateJs("typeof __sbg_scout.openScoutSettings === 'function'"),
        )

        // Маркер одноразовой инициализации bootstrap-скрипта.
        assertEquals(
            "true",
            game.evaluateJs("window.__sbg_scout_bootstrapped === true"),
        )
    }
}
