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
 * Установка пресетов через embedded-менеджер скриптов в GameActivity.
 *
 * Нюанс: SVP (SBG Vanilla+) приходит вшитым в APK через BundledScriptInstaller
 * и устанавливается автоматически при первом запуске GameActivity в
 * `setupWebView`. Значит пользователь физически не увидит «download» для SVP —
 * он всегда уже установлен. Download-flow применим только к не-bundled пресетам
 * (EUI, CUI) и кастомным скриптам.
 *
 * Поэтому тест на SVP проверяет BundledScriptInstaller, а тест на EUI —
 * реальный клик по download-иконке + HTTP-flow через FakeGameServer.
 */
class ScriptManagerInstallPresetE2ETest : E2ETestBase() {

    @Test
    fun bundledSvp_isPreinstalledAfterFirstGameActivityLaunch() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()
        scriptManager.waitForCard("SBG Vanilla+")

        // BundledScriptInstaller.installBundled в GameActivity.setupWebView
        // установил SVP из app/src/main/assets/scripts/sbg-vanilla-plus.user.js.
        val storage = ScriptStorageFixture.storage()
        val svp = storage.getAll().find {
            it.header.namespace == "github.com/wrager/sbg-vanilla-plus"
        } ?: fail("SVP должен быть автоматически установлен BundledScriptInstaller").let {
            throw AssertionError("unreachable")
        }

        assertTrue("Bundled SVP помечен isPreset=true", svp.isPreset)
        assertTrue(
            "Bundled SVP включён по умолчанию (enabledByDefault=true у пресета)",
            svp.enabled,
        )
        assertEquals(
            "sourceUrl совпадает с preset.downloadUrl",
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
