package com.github.wrager.sbgscout.e2e.flows.scripts

import android.view.View
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
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

    /**
     * Клик по `reloadButton` в embedded ScriptListFragment закрывает весь overlay
     * (ScriptListFragment.setupButtons → `dismissSettings` вместо `closeSettings`:
     * один вызов очищает back stack и скрывает settingsContainer) и приводит к
     * повторной загрузке игры — `applySettingsAfterClose` читает флаг
     * `KEY_RELOAD_REQUESTED` и делает `webView.loadUrl(GameUrls.appUrl)`, поэтому
     * fake-сервер получает второй `GET /app`.
     */
    @Test
    fun reloadButton_click_dismissesOverlayAndReloadsGame() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        // Вырезаем первый GET /app из очереди — он прилетает на старте загрузки игры.
        server.takeRequestMatching { it.path == "/app" && it.method == "GET" }

        ScriptStorageFixture.storage().save(
            ScriptStorageFixture.minimalScript(
                name = "Custom Reload Click Script",
                enabled = true,
                identifierValue = "custom-reload-click-script",
            ),
        )

        val scriptManager = game.openSettings().openManageScripts()
        scriptManager.waitForCard("Custom Reload Click Script")
        assertTrue(
            "reloadButton должен быть VISIBLE до клика",
            scriptManager.isReloadButtonVisible(),
        )

        scriptManager.clickReloadGame()

        // После клика GameActivity.dismissSettings скрывает settingsContainer целиком,
        // а applySettingsAfterClose делает повторный webView.loadUrl(GameUrls.appUrl).
        scenario.onActivity { activity: GameActivity ->
            val container = activity.findViewById<View>(R.id.settingsContainer)
            val topButtons = activity.findViewById<View>(R.id.topButtonsContainer)
            assertEquals(
                "settingsContainer должен быть GONE после reloadButton click",
                View.GONE,
                container.visibility,
            )
            assertEquals(
                "topButtonsContainer должен быть VISIBLE после закрытия overlay",
                View.VISIBLE,
                topButtons.visibility,
            )
        }

        val second = server.takeRequestMatching(RELOAD_REQUEST_TIMEOUT_MS) {
            it.path == "/app" && it.method == "GET"
        }
        assertEquals("/app", second.path)
    }

    private companion object {
        const val RELOAD_REQUEST_TIMEOUT_MS = 5_000L
    }
}
