package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.Assert.fail
import org.junit.Test

/**
 * Check updates + update scripts через embedded-менеджер в GameActivity.
 *
 * 1. Sideload SVP v0.8.0 (как preset). Stub .meta.js и .user.js с v0.8.1.
 * 2. Клик "Check for updates" → LauncherViewModel.checkUpdates → UpdateAvailable.
 * 3. Клик "Update all" (текст кнопки меняется после checkUpdates) → applyUpdate.
 * 4. Storage обновляется до 0.8.1.
 */
class ScriptManagerUpdateE2ETest : E2ETestBase() {

    @Test
    fun updateSingleScript_viaUpdateAllButton_installsNewVersion() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0")
        val svp081 = AssetLoader.read("fixtures/scripts/svp-v0.8.1.user.js")
        server.stubScriptAsset(
            "wrager", "sbg-vanilla-plus", "latest", "sbg-vanilla-plus.meta.js", svp081,
        )
        server.stubScriptAsset(
            "wrager", "sbg-vanilla-plus", "latest", "sbg-vanilla-plus.user.js", svp081,
        )

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.clickCheckUpdates()
        // После checkUpdates нужно дождаться, пока viewModel.uiState обновится,
        // и сразу кликать на ту же кнопку — её label становится "Update all",
        // но id тот же. ScriptManagerScreen.clickCheckUpdates идёт по id.
        waitForSvpVersion("0.8.0")
        scriptManager.clickCheckUpdates() // теперь это "Update all"

        waitForSvpVersion("0.8.1")
    }

    private fun waitForSvpVersion(expected: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        val storage = ScriptStorageFixture.storage()
        while (SystemClock.uptimeMillis() < deadline) {
            val version = storage.getAll()
                .find { it.header.namespace == "github.com/wrager/sbg-vanilla-plus" }
                ?.header?.version
            if (version == expected) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("SVP не достиг версии $expected за ${TIMEOUT_MS}ms")
    }

    private fun sideloadSvp(version: String) {
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
                content = "// sideloaded SVP $version",
                enabled = true,
                isPreset = true,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
