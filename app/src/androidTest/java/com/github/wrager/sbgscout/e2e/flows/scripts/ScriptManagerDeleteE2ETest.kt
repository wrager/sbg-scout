package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.junit.Assert.fail
import org.junit.Test

/**
 * Клик «Delete» в overflow-меню карточки + подтверждение в диалоге удаляет
 * скрипт из ScriptStorage (SharedPreferences + файл контента).
 */
class ScriptManagerDeleteE2ETest : E2ETestBase() {

    @Test
    fun delete_installedScript_removesFromStorage() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val script = ScriptStorageFixture.minimalScript(
            name = "Deletable Script",
            identifierValue = "deletable-script",
        )
        val storage = ScriptStorageFixture.storage()
        storage.save(script)

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()

        scriptManager.deleteCardViaOverflow("Deletable Script")

        waitUntilDeleted(storage, script.identifier.value)
    }

    private fun waitUntilDeleted(storage: ScriptStorage, identifierValue: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val found = storage.getAll().find { it.identifier.value == identifierValue }
            if (found == null) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Script $identifierValue не удалён из storage за ${TIMEOUT_MS}ms")
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
