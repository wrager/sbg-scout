package com.github.wrager.sbgscout.e2e.flows.scripts

import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кнопка «Reload game» в embedded ScriptListFragment появляется, когда
 * текущий enabled-снапшот отличается от snapshot'а последней инжекции в WebView.
 *
 * Flow:
 * 1. launchGameActivity → setupWebView → BundledScriptInstaller ставит SVP →
 *    webView.loadUrl(fake /app) → SbgWebViewClient.onPageStarted →
 *    ScriptInjector.inject сохраняет snapshot = {bundled SVP} в
 *    InjectionStateStorage.
 * 2. Sideload кастомного enabled-скрипта — он НЕ был частью snapshot'а.
 * 3. Открываем settings overlay → Manage scripts → LauncherViewModel
 *    refreshScriptList видит current={bundled SVP + custom} != snapshot={bundled SVP}
 *    → reloadNeeded=true → reloadButton VISIBLE.
 *
 * Использование кастомного скрипта (не preset) исключает гонки с
 * BundledScriptInstaller, который автоматически переустанавливает SVP.
 */
class ScriptManagerReloadButtonE2ETest : E2ETestBase() {

    @Test
    fun reloadButton_visibleAfterEnablingNewCustomScript() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        // К этому моменту ScriptInjector.inject уже сохранил snapshot с тем,
        // что было в storage (bundled SVP).

        // Добавляем новый enabled скрипт поверх snapshot'а.
        ScriptStorageFixture.storage().save(
            ScriptStorageFixture.minimalScript(
                name = "Custom Reload Script",
                enabled = true,
                identifierValue = "custom-reload-script",
            ),
        )

        val scriptManager = game.openSettings().openManageScripts()
        scriptManager.waitForCard("Custom Reload Script")

        assertTrue(
            "reloadButton должен быть VISIBLE при добавлении нового enabled-скрипта после inject",
            scriptManager.isReloadButtonVisible(),
        )
    }
}
