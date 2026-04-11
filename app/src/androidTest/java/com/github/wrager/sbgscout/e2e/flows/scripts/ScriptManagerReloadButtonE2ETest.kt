package com.github.wrager.sbgscout.e2e.flows.scripts

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кнопка «Reload game» в embedded ScriptListFragment появляется, когда текущий
 * enabled-снапшот скриптов отличается от snapshot'а последней инжекции.
 */
class ScriptManagerReloadButtonE2ETest : E2ETestBase() {

    @Test
    fun reloadButton_visibleAfterScriptChange() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val injectionStorage = InjectionStateStorage(
            targetContext.getSharedPreferences("scripts", Context.MODE_PRIVATE),
        )
        // Предыдущая сессия использовала SVP v0.8.0.
        injectionStorage.saveSnapshot(listOf(buildSvpScript("0.8.0", enabled = true)))

        // Текущий стор: SVP v0.8.1 enabled (отличается от snapshot'а).
        ScriptStorageFixture.storage().save(buildSvpScript("0.8.1", enabled = true))

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        assertTrue(
            "reloadButton должен быть VISIBLE при расхождении snapshot ↔ current enabled",
            scriptManager.isReloadButtonVisible(),
        )
    }

    private fun buildSvpScript(version: String, enabled: Boolean): UserScript = UserScript(
        identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
        header = ScriptHeader(
            name = "SBG Vanilla+",
            version = version,
            namespace = "github.com/wrager/sbg-vanilla-plus",
            match = listOf("https://sbg-game.ru/app/*"),
        ),
        sourceUrl = PresetScripts.SVP.downloadUrl,
        updateUrl = PresetScripts.SVP.updateUrl,
        content = "// SVP $version",
        enabled = enabled,
        isPreset = true,
    )
}
