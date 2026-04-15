package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что [com.github.wrager.sbgscout.script.injector.ScriptInjector.wrapInSafeIife]
 * уважает семантику `@run-at`:
 *
 *  - `document-idle` запускает `run()` не раньше `window.load`, т.е. в момент
 *    вызова `document.readyState === 'complete'`.
 *  - `document-end` (и default) запускает `run()` на `DOMContentLoaded`, т.е.
 *    `document.readyState` в момент вызова — `'interactive'` либо `'complete'`.
 *
 * Это прямой регрессионный тест под правку, после которой перестали
 * пропадать пункты EUI (Color Scheme, Show speed) при включённом CUI: CUI
 * объявляет `@run-at document-idle` и в начале делает
 * `window.stop() + document.open() + document.write(fetched /app)` — если
 * обёртка запускает его слишком рано (на DOMContentLoaded), возникает гонка
 * с deferred module-скриптом игры.
 */
@RunWith(AndroidJUnit4::class)
class ScriptInjectionRunAtE2ETest : E2ETestBase() {

    @Test
    fun documentIdle_runsAfterWindowLoad_documentEndRunsAfterDomContentLoaded() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val storage = ScriptStorageFixture.storage()
        storage.save(probeScript("runat-idle-probe", "Idle Probe", "document-idle"))
        storage.save(probeScript("runat-end-probe", "End Probe", "document-end"))

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        // Оба скрипта должны успеть отстрелить — onGamePageFinished (и соответственно
        // waitForLoaded) срабатывает после window.load.
        assertEquals("true", game.evaluateJs("window.__sbg_e2e_idle_fired === true"))
        assertEquals("true", game.evaluateJs("window.__sbg_e2e_end_fired === true"))

        // document-idle: run() вызывается из window.load listener, т.е. в момент
        // вызова readyState уже 'complete'.
        assertEquals(
            "\"complete\"",
            game.evaluateJs("window.__sbg_e2e_idle_readyState"),
        )

        // document-end: run() вызывается на DOMContentLoaded. На этот момент
        // readyState может быть 'interactive' (DOMContentLoaded уже прошёл, но
        // load ещё нет) либо 'complete' (если страница настолько мала, что load
        // произошёл в той же микротаске). Оба значения валидны — семантика
        // DOMContentLoaded не требует конкретного состояния между ними.
        val endReadyState = game.evaluateJs("window.__sbg_e2e_end_readyState")
        assertEquals(
            "document-end должен видеть 'interactive' или 'complete', был: $endReadyState",
            true,
            endReadyState == "\"interactive\"" || endReadyState == "\"complete\"",
        )
    }

    private fun probeScript(
        identifier: String,
        name: String,
        runAt: String,
    ): UserScript {
        val globalPrefix = when (runAt) {
            "document-idle" -> "__sbg_e2e_idle"
            "document-end" -> "__sbg_e2e_end"
            else -> error("Unexpected runAt in probe: $runAt")
        }
        val body = """
            window.${globalPrefix}_readyState = document.readyState;
            window.${globalPrefix}_fired = true;
        """.trimIndent()
        return UserScript(
            identifier = ScriptIdentifier(identifier),
            header = ScriptHeader(
                name = name,
                version = "1.0.0",
                namespace = "e2e-tests",
                match = listOf("https://sbg-game.ru/app/*"),
                runAt = runAt,
            ),
            sourceUrl = "https://example.com/$identifier.user.js",
            updateUrl = null,
            content = """
                // ==UserScript==
                // @name $name
                // @namespace e2e-tests
                // @version 1.0.0
                // @match https://sbg-game.ru/app/*
                // @run-at $runAt
                // ==/UserScript==
                $body
            """.trimIndent(),
            enabled = true,
        )
    }
}
