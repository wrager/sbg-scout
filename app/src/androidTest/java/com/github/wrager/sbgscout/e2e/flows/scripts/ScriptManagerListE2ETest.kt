package com.github.wrager.sbgscout.e2e.flows.scripts

import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Проверяет, что в embedded-менеджере скриптов (ScriptListFragment в settings
 * overlay GameActivity) отображаются все три пресета + sideloaded custom скрипт.
 */
class ScriptManagerListE2ETest : E2ETestBase() {

    @Test
    fun emptyStorage_showsAllThreePresetsInList() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        val scriptManager = game.openSettings().openManageScripts()

        for (presetName in listOf("SBG Vanilla+", "SBG Enhanced UI", "SBG CUI")) {
            assertNotNull(
                "Пресет '$presetName' должен отображаться в списке",
                scriptManager.cardView(presetName),
            )
        }
    }

    @Test
    fun sideloadedCustomScript_showsInList() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val script = ScriptStorageFixture.minimalScript("My Custom Script", version = "2.3.4")
        ScriptStorageFixture.storage().save(script)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        val scriptManager = game.openSettings().openManageScripts()

        assertNotNull(
            "Sideloaded скрипт должен быть в списке",
            scriptManager.cardView("My Custom Script"),
        )
    }
}
