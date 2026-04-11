package com.github.wrager.sbgscout.e2e.flows.scripts

import android.view.View
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * StaticConflictRules через embedded-менеджер скриптов в GameActivity:
 * - SVP v0.8.0 + EUI v8.1.0 → conflictContainer VISIBLE на обеих карточках.
 * - SVP v0.8.0 + EUI v8.2.0 → compatibleSince разрешает конфликт, GONE.
 */
class ScriptManagerConflictE2ETest : E2ETestBase() {

    @Test
    fun svpPlusOldEui_showsConflictOnBothCards() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0", enabled = true)
        sideloadEui("8.1.0", enabled = true)

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        val svp = scriptManager.cardView("SBG Vanilla+")
            ?: throw AssertionError("SVP card not found")
        val eui = scriptManager.cardView("SBG Enhanced UI")
            ?: throw AssertionError("EUI card not found")

        assertEquals(
            "SVP карточка должна показывать conflictContainer",
            View.VISIBLE,
            svp.findViewById<View>(R.id.conflictContainer).visibility,
        )
        assertEquals(
            "EUI карточка должна показывать conflictContainer",
            View.VISIBLE,
            eui.findViewById<View>(R.id.conflictContainer).visibility,
        )
    }

    @Test
    fun svpPlusNewEui_hasNoConflict() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0", enabled = true)
        sideloadEui("8.2.0", enabled = true)

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        val svp = scriptManager.cardView("SBG Vanilla+")
            ?: throw AssertionError("SVP card not found")
        val eui = scriptManager.cardView("SBG Enhanced UI")
            ?: throw AssertionError("EUI card not found")

        assertEquals(
            View.GONE,
            svp.findViewById<View>(R.id.conflictContainer).visibility,
        )
        assertEquals(
            View.GONE,
            eui.findViewById<View>(R.id.conflictContainer).visibility,
        )
    }

    private fun sideloadSvp(version: String, enabled: Boolean) {
        ScriptStorageFixture.storage().save(
            UserScript(
                identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
                header = ScriptHeader(
                    name = "SBG Vanilla+",
                    version = version,
                    namespace = "github.com/wrager/sbg-vanilla-plus",
                    match = listOf("https://sbg-game.ru/app/*"),
                ),
                sourceUrl = PresetScripts.SVP.downloadUrl,
                updateUrl = PresetScripts.SVP.updateUrl,
                content = "// fixture SVP v$version",
                enabled = enabled,
                isPreset = true,
            ),
        )
    }

    private fun sideloadEui(version: String, enabled: Boolean) {
        ScriptStorageFixture.storage().save(
            UserScript(
                identifier = ScriptIdentifier("github.com/egorantonov/sbg-enhanced/SBG Enhanced UI"),
                header = ScriptHeader(
                    name = "SBG Enhanced UI",
                    version = version,
                    namespace = "github.com/egorantonov/sbg-enhanced",
                    match = listOf("https://sbg-game.ru/app/*"),
                ),
                sourceUrl = PresetScripts.EUI.downloadUrl,
                updateUrl = PresetScripts.EUI.updateUrl,
                content = "// fixture EUI v$version",
                enabled = enabled,
                isPreset = true,
            ),
        )
    }
}
