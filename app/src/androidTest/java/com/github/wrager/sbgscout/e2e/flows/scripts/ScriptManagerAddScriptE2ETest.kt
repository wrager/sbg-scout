package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Добавление скрипта через "Add script" → dialog → ввод URL → Add.
 *
 * Happy path: valid header в ответе → скрипт сохраняется.
 * Negative path: нет header'а → скрипт НЕ сохраняется.
 */
class ScriptManagerAddScriptE2ETest : E2ETestBase() {

    @Test
    fun addScriptViaUrl_validHeader_savesScriptInStorage() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        server.stubScriptAsset(
            "user", "repo", "latest", "script.user.js",
            AssetLoader.read("fixtures/scripts/custom-v1.0.0.user.js"),
        )

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.openAddScriptDialogAndSubmitUrl(
            "https://github.com/user/repo/releases/latest/download/script.user.js",
        )

        val storage = ScriptStorageFixture.storage()
        val saved = waitForScript(storage) { it.header.name == "Custom E2E Script" }
        assertEquals("1.0.0", saved.header.version)
        assertFalse("Кастомный скрипт не должен быть isPreset", saved.isPreset)
    }

    @Test
    fun addScriptViaUrl_invalidHeader_doesNotSaveScript() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        server.stubScriptAsset(
            "user", "repo", "latest", "broken.user.js",
            AssetLoader.read("fixtures/scripts/no-header.user.js"),
        )

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.openAddScriptDialogAndSubmitUrl(
            "https://github.com/user/repo/releases/latest/download/broken.user.js",
        )

        // Даём время корутине отработать download + parse.
        Thread.sleep(2_000L)

        val storage = ScriptStorageFixture.storage()
        val saved = storage.getAll().find {
            it.sourceUrl?.contains("broken.user.js") == true
        }
        assertNull("Скрипт без UserScript header не должен сохраняться", saved)
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
