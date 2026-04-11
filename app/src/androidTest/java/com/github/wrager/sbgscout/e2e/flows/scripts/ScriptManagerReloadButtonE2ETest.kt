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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кнопка «Reload game» в embedded ScriptListFragment появляется, когда
 * текущий enabled-снапшот скриптов отличается от snapshot'а последней
 * инжекции в WebView.
 *
 * Важный нюанс: `ScriptInjector.inject` в `SbgWebViewClient.onPageStarted`
 * перезаписывает InjectionStateStorage snapshot на основании enabled-скриптов
 * в момент загрузки /app. Поэтому нельзя «подготовить» snapshot до launch —
 * он будет перезаписан. Правильный flow теста:
 *
 * 1. Sideload SVP v0.8.0 enabled.
 * 2. launchGameActivity → WebView грузит /app → inject сохраняет snapshot {SVP v0.8.0}.
 * 3. Ждём waitForLoaded (snapshot уже записан).
 * 4. Переписываем storage: SVP v0.8.1 enabled (отличается от snapshot'а по version).
 * 5. Открываем settings overlay → Manage scripts → LauncherViewModel.refreshScriptList
 *    видит snapshot={v0.8.0} != current={v0.8.1} → reloadNeeded=true → button VISIBLE.
 */
class ScriptManagerReloadButtonE2ETest : E2ETestBase() {

    @Test
    fun reloadButton_visibleAfterScriptChange() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        // Этап 1: state до load игры — SVP v0.8.0 enabled.
        ScriptStorageFixture.storage().save(buildSvpScript("0.8.0", enabled = true))

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        // Подождать, пока ScriptInjector действительно сохранит snapshot (inject
        // идёт после evaluateJavascript и не гарантированно синхронен с onPageFinished).
        SystemClock.sleep(INJECT_SETTLE_MS)

        // Этап 2: обновляем state поверх загруженной игры — SVP v0.8.1 enabled.
        val storage = ScriptStorageFixture.storage()
        storage.delete(ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"))
        storage.save(buildSvpScript("0.8.1", enabled = true))

        // Этап 3: открываем overlay → script manager → LauncherViewModel видит
        // расхождение snapshot ↔ current и ставит reloadNeeded=true.
        val scriptManager = game.openSettings().openManageScripts()
        scriptManager.waitForCard("SBG Vanilla+")

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

    private companion object {
        const val INJECT_SETTLE_MS = 300L
    }
}
