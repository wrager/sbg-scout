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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reinstall не-github-hosted скрипта через overflow menu.
 *
 * Поток:
 * 1. Stub fake-сервер на non-GitHub пути
 *    `/gh-web/custom-owner/custom-repo/releases/latest/download/custom-script.user.js`
 *    — URL не содержит `github.com`, значит
 *    [com.github.wrager.sbgscout.launcher.ScriptUiItem.isGithubHosted] = false,
 *    и overflow menu показывает «Reinstall» (а не «Select version»).
 * 2. Sideload кастомный скрипт с content="ORIGINAL", enabled=true и sourceUrl,
 *    указывающим на этот fake endpoint.
 * 3. Launch GameActivity → ScriptInjector.inject сохраняет snapshot
 *    {enabled custom script @hash(ORIGINAL)}.
 * 4. Open settings → manage scripts → overflow → Reinstall →
 *    LauncherViewModel.reinstallScript фетчит sourceUrl и сохраняет новый
 *    распарсенный скрипт.
 * 5. Ждём, пока `storage.getAll()` вернёт содержимое с маркером REINSTALLED
 *    (доказательство, что reinstall завершился — тот же effect, что
 *    LauncherEvent.ReinstallCompleted → toast reinstall_completed).
 * 6. Проверяем: reloadButton VISIBLE (refreshScriptList сравнивает новый
 *    snapshot с lastInjectedSnapshot — content hash изменился → reloadNeeded).
 */
class ScriptManagerReinstallE2ETest : E2ETestBase() {

    @Test
    fun reinstallCustomScript_viaOverflow_updatesStorageAndShowsReloadButton() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        // Sideload custom скрипт c content="ORIGINAL", enabled=true. sourceUrl
        // указывает на fake endpoint, который stub'ится ниже.
        val reinstallUrl = "${server.baseUrl}/gh-web/custom-owner/custom-repo/releases/latest/download/custom-script.user.js"
        val originalContent = buildScriptContent(
            name = SCRIPT_NAME,
            namespace = NAMESPACE,
            version = "1.0.0",
            marker = "ORIGINAL",
        )
        val reinstalledContent = buildScriptContent(
            name = SCRIPT_NAME,
            namespace = NAMESPACE,
            version = "1.0.1",
            marker = "REINSTALLED",
        )
        // Устанавливаем скрипт напрямую в storage с тем же identifier, который
        // получится после reparse после reinstall (namespace + "/" + name).
        val identifier = ScriptIdentifier("$NAMESPACE/$SCRIPT_NAME")
        ScriptStorageFixture.storage().save(
            UserScript(
                identifier = identifier,
                header = ScriptHeader(
                    name = SCRIPT_NAME,
                    version = "1.0.0",
                    namespace = NAMESPACE,
                    match = listOf("https://sbg-game.ru/app/*"),
                ),
                sourceUrl = reinstallUrl,
                updateUrl = null,
                content = originalContent,
                enabled = true,
                isPreset = false,
            ),
        )

        // Stub путь, по которому reinstall пойдёт. Формат ключа в FakeGameServer:
        // "<owner>/<repo>/<tag>/<filename>". Для /releases/latest/download/ tag = "latest".
        server.stubScriptAsset(
            "custom-owner",
            "custom-repo",
            "latest",
            "custom-script.user.js",
            reinstalledContent,
        )

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        // После загрузки snapshot содержит originalContent-хеш.

        val scriptManager = game.openSettings().openManageScripts()
        scriptManager.waitForCard(SCRIPT_NAME)

        scriptManager.reinstallCardViaOverflow(SCRIPT_NAME)

        // Ждём, пока storage получит новый content. LauncherViewModel.reinstallScript
        // отправляет ReinstallCompleted только после scriptStorage.save через downloader.
        waitUntilScriptContentContains("REINSTALLED")

        val stored = ScriptStorageFixture.storage().getAll()
            .find { it.header.name == SCRIPT_NAME }
            ?: fail("Скрипт '$SCRIPT_NAME' не найден в storage после reinstall").let { error("unreachable") }
        assertEquals(
            "После reinstall версия должна обновиться до 1.0.1",
            "1.0.1",
            stored.header.version,
        )
        assertTrue(
            "Скрипт должен остаться enabled (LauncherViewModel.reinstallScript сохраняет enabled-статус)",
            stored.enabled,
        )

        // reloadButton появляется, когда refreshScriptList обнаруживает, что
        // новый snapshot (с новым content hash) != lastInjectedSnapshot (с старым hash).
        waitUntilReloadButtonVisible(scriptManager)
    }

    private fun waitUntilScriptContentContains(marker: String) {
        val deadline = SystemClock.uptimeMillis() + STORAGE_WAIT_TIMEOUT_MS
        val storage = ScriptStorageFixture.storage()
        while (SystemClock.uptimeMillis() < deadline) {
            val content = storage.getAll()
                .find { it.header.name == SCRIPT_NAME }
                ?.content
                .orEmpty()
            if (content.contains(marker)) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("storage content не стал содержать маркер '$marker' за ${STORAGE_WAIT_TIMEOUT_MS}ms")
    }

    private fun waitUntilReloadButtonVisible(
        scriptManager: com.github.wrager.sbgscout.e2e.screens.ScriptManagerScreen,
    ) {
        val deadline = SystemClock.uptimeMillis() + RELOAD_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (scriptManager.isReloadButtonVisible()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("reloadButton не стал VISIBLE за ${RELOAD_WAIT_TIMEOUT_MS}ms после reinstall")
    }

    private fun buildScriptContent(
        name: String,
        namespace: String,
        version: String,
        marker: String,
    ): String = """
        // ==UserScript==
        // @name $name
        // @namespace $namespace
        // @version $version
        // @match https://sbg-game.ru/app/*
        // ==/UserScript==
        console.log('$marker: $name v$version');
    """.trimIndent()

    private companion object {
        const val SCRIPT_NAME = "Custom Reinstall Script"
        const val NAMESPACE = "e2e-custom-reinstall"
        const val STORAGE_WAIT_TIMEOUT_MS = 10_000L
        const val RELOAD_WAIT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
