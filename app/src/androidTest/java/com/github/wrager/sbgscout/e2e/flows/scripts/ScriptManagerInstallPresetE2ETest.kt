package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Установка пресета SBG Vanilla+ / SBG Enhanced UI через клик по download-иконке
 * в карточке. Полный цикл: LauncherViewModel.downloadScript → ScriptDownloader
 * → HttpFetcher (с urlRewriter на FakeGameServer) → ScriptInstaller → storage.
 */
class ScriptManagerInstallPresetE2ETest : E2ETestBase() {

    @Test
    fun downloadSvpPreset_savesScriptAsPresetAndEnabledByDefault() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        server.stubScriptAsset(
            owner = "wrager",
            repo = "sbg-vanilla-plus",
            tag = "latest",
            filename = "sbg-vanilla-plus.user.js",
            content = AssetLoader.read("fixtures/scripts/svp-v0.8.0.user.js"),
        )

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.clickCardChildView("SBG Vanilla+", R.id.actionButton)

        val storage = ScriptStorageFixture.storage()
        val svp = waitForScript(storage) {
            it.header.namespace == "github.com/wrager/sbg-vanilla-plus" &&
                it.header.name == "SBG Vanilla+" &&
                it.enabled
        }

        assertEquals("0.8.0", svp.header.version)
        assertTrue("SVP должен быть enabled=true по умолчанию", svp.enabled)
        assertTrue("Сохранённый скрипт должен быть isPreset=true", svp.isPreset)
        assertEquals(
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
            svp.sourceUrl,
        )
    }

    @Test
    fun downloadEuiPreset_savesScriptAsPresetAndDisabledByDefault() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        server.stubScriptAsset(
            owner = "egorantonov",
            repo = "sbg-enhanced",
            tag = "latest",
            filename = "eui.user.js",
            content = AssetLoader.read("fixtures/scripts/eui-v8.2.0.user.js"),
        )

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.clickCardChildView("SBG Enhanced UI", R.id.actionButton)

        val storage = ScriptStorageFixture.storage()
        val eui = waitForScript(storage) {
            it.header.namespace == "github.com/egorantonov/sbg-enhanced"
        }

        assertEquals("8.2.0", eui.header.version)
        assertFalse(
            "EUI без enabledByDefault должен быть disabled после скачивания",
            eui.enabled,
        )
        assertTrue("EUI помечен isPreset=true", eui.isPreset)
    }

    private fun waitForScript(
        storage: ScriptStorage,
        predicate: (UserScript) -> Boolean,
    ): UserScript {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val match = storage.getAll().find(predicate)
            if (match != null) return match
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Скрипт не появился в storage за ${TIMEOUT_MS}ms")
        throw AssertionError("unreachable")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
