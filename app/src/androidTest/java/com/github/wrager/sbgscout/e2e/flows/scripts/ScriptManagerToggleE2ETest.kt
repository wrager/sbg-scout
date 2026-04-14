package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.junit.Assert.fail
import org.junit.Test

/**
 * Клик по SwitchCompat карточки скрипта в embedded-менеджере пишет новое
 * значение `enabled` в ScriptStorage через LauncherViewModel.toggleScript.
 */
class ScriptManagerToggleE2ETest : E2ETestBase() {

    @Test
    fun toggle_enablesDisabledScript() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val script = ScriptStorageFixture.minimalScript(
            name = "Toggle Test",
            enabled = false,
            identifierValue = "toggle-test-enable",
        )
        val storage = ScriptStorageFixture.storage()
        storage.save(script)

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.clickCardChildView("Toggle Test", R.id.scriptToggle)

        waitUntilEnabled(storage, script.identifier.value, expected = true)
    }

    @Test
    fun toggle_disablesEnabledScript() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val script = ScriptStorageFixture.minimalScript(
            name = "Toggle Test",
            enabled = true,
            identifierValue = "toggle-test-disable",
        )
        val storage = ScriptStorageFixture.storage()
        storage.save(script)

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.clickCardChildView("Toggle Test", R.id.scriptToggle)

        waitUntilEnabled(storage, script.identifier.value, expected = false)
    }

    private fun waitUntilEnabled(
        storage: ScriptStorage,
        identifierValue: String,
        expected: Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val actual = storage.getAll().find { it.identifier.value == identifierValue }?.enabled
            if (actual == expected) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail(
            "Скрипт $identifierValue не получил enabled=$expected за ${TIMEOUT_MS}ms " +
                "(текущее: ${storage.getAll().find { it.identifier.value == identifierValue }?.enabled})",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
