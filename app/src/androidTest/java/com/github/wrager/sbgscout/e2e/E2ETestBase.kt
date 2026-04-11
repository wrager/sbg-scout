package com.github.wrager.sbgscout.e2e

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.IdlingRegistry
import androidx.test.rule.GrantPermissionRule
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.FakeGameServer
import com.github.wrager.sbgscout.e2e.infra.GameUrlsOverrideRule
import com.github.wrager.sbgscout.e2e.infra.WebViewIdlingResource
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Базовый класс e2e-тестов.
 *
 * Отвечает за жизненный цикл fake-сервера, IdlingResource, runtime-override
 * URL в [com.github.wrager.sbgscout.config.GameUrls], grant location permission.
 * Activity НЕ запускается автоматически — каждый тест делает это сам, т.к.
 * разным сценариям нужен разный setup (логин/не-логин, разные фикстуры HTML).
 *
 * Типовое использование:
 * ```
 * class MyE2ETest : E2ETestBase() {
 *     @Test fun something() {
 *         server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
 *         CookieFixtures.injectFakeAuth(server.baseUrl)
 *         val scenario = launchGameActivity()
 *         val game = GameScreen(scenario, idling).waitForLoaded()
 *         // ...
 *     }
 * }
 * ```
 */
abstract class E2ETestBase {

    protected val server = FakeGameServer()
    protected val idling = WebViewIdlingResource()

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    @get:Rule(order = 1)
    val gameUrlsRule = GameUrlsOverrideRule { server.baseUrl }

    private var activeScenario: ActivityScenario<GameActivity>? = null

    @Before
    fun setUpE2E() {
        server.start()
        IdlingRegistry.getInstance().register(idling)
    }

    @After
    fun tearDownE2E() {
        activeScenario?.close()
        activeScenario = null
        IdlingRegistry.getInstance().unregister(idling)
        CookieFixtures.clearAll()
        server.shutdown()
    }

    /**
     * Запускает [GameActivity] и подписывает idling на `onGamePageFinished`.
     * После возврата можно использовать [GameScreen.waitForLoaded].
     */
    protected fun launchGameActivity(): ActivityScenario<GameActivity> {
        val scenario = ActivityScenario.launch(GameActivity::class.java)
        scenario.onActivity { activity ->
            activity.sbgWebViewClient.onGamePageFinished = {
                idling.markLoaded()
            }
        }
        activeScenario = scenario
        return scenario
    }
}
