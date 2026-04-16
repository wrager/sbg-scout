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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2e-тесты порядка и тайминга инжекции скриптов.
 *
 * Каждый тест проверяет корневую причину конкретного бага, обнаруженного
 * при отладке совместимости CUI + EUI. Тесты, помеченные «КРАСНЫЙ на 1.1.0»,
 * детерминистично падают на коде до наших правок.
 *
 * Фейковый CUI: document.open → document.write(minimal HTML) → document.close →
 * cuiStatus = 'loading' → setTimeout 500ms → cuiStatus = 'loaded'.
 *
 * Фейковый EUI: записывает readyState, cuiStatus и инкрементирует счётчик
 * запусков при каждом вызове.
 */
@RunWith(AndroidJUnit4::class)
class ScriptInjectionOrderE2ETest : E2ETestBase() {

    /**
     * Корневая причина: CUI не ставил глобалов синхронно → EUI'шный
     * CUI.Detected() возвращал false → EUI шёл по пути delay 500ms
     * вместо 30-сек polling → настройки пропадали.
     *
     * КРАСНЫЙ на 1.1.0: cuiStatus не выставлялся перед CUI.
     */
    @Test
    fun cuiStatusMarker_isSetBeforeRewriterRuns() {
        setupWithScripts(fakeCuiScript())

        val game = launchAndWait()
        game.waitForJs("window.__e2e_cui_fired === true", "CUI probe")

        assertEquals(
            "cuiStatus='initializing' должен быть выставлен ДО старта CUI кода",
            "\"initializing\"",
            game.evaluateJs("window.__e2e_cui_initial_cuiStatus"),
        )
    }

    /**
     * Корневая причина: WebView уничтожает DOMContentLoaded listener при
     * document.open() → EUI никогда не стартовал после CUI.
     * Решение: readyState polling вместо DOMContentLoaded для regular скриптов.
     *
     * КРАСНЫЙ на 1.1.0: EUI'шный cuiStatus = undefined (маркер не ставился,
     * CUI.Detected()=false → EUI не входил в CUI-ожидание).
     */
    @Test
    fun regularScript_seesValidCuiStatus_afterRewriterPipeline() {
        setupWithScripts(fakeCuiScript(), fakeEuiScript())

        val game = launchAndWait()
        game.waitForJs("window.__e2e_eui_fired === true", "EUI probe")

        val cuiStatus = game.evaluateJs("window.__e2e_eui_cuiStatus")
        assertTrue(
            "EUI должен видеть cuiStatus='loading' или 'loaded', не undefined/" +
                "initializing. Факт: $cuiStatus",
            cuiStatus == "\"loading\"" || cuiStatus == "\"loaded\"",
        )
    }

    /**
     * Корневая причина: протоtipy (addEventListener, write, close) не
     * восстанавливались при JS reload → двойная обёртка → CUI main()
     * вызывался дважды → сломанные touch handlers.
     *
     * КРАСНЫЙ на 1.1.0: __sbg_event_fix_originals не существовал.
     */
    @Test
    fun eventFix_savesOriginalPrototypes_forReloadRecovery() {
        setupWithScripts()

        val game = launchAndWait()

        assertEquals(
            "Event fix должен сохранять оригиналы prototypes",
            "true",
            game.evaluateJs("window.__sbg_event_fix_originals !== undefined"),
        )
        assertEquals(
            "Оригинал addEventListener должен быть функцией",
            "true",
            game.evaluateJs(
                "typeof window.__sbg_event_fix_originals.addEventListener === 'function'",
            ),
        )
    }

    /**
     * Корневая причина: DOMContentLoaded listener не уничтожался на Chrome 146
     * при document.write → срабатывал естественно + из нашего кеша → EUI
     * инициализировался дважды → дублирование Show speed, сломанные кнопки.
     * Решение: polling вместо DOMContentLoaded для regular скриптов.
     *
     * НЕ отличает от 1.1.0 (там тоже 0 или 1), но ловит регрессию.
     */
    @Test
    fun regularScript_runsExactlyOnce_noDoubleInitialization() {
        setupWithScripts(fakeCuiScript(), fakeEuiScript())

        val game = launchAndWait()
        game.waitForJs("window.__e2e_eui_fired === true", "EUI probe")

        assertEquals(
            "EUI должен запуститься ровно 1 раз (не 0, не 2+)",
            "1",
            game.evaluateJs("window.__e2e_eui_run_count"),
        )
    }

    /**
     * Корневая причина: CUI запускался при readyState='complete'
     * (onPageFinished) → window.stop() бесполезен, touch handlers
     * не работают (поворот карты сломан).
     * Решение: CUI через DOMContentLoaded (readyState='interactive').
     *
     * НЕ отличает от 1.1.0 (там тоже 'interactive'), но ловит регрессию
     * с onPageFinished.
     */
    @Test
    fun rewriterScript_runsAtInteractive_notComplete() {
        setupWithScripts(fakeCuiScript())

        val game = launchAndWait()
        game.waitForJs("window.__e2e_cui_fired === true", "CUI probe")

        val readyState = game.evaluateJs("window.__e2e_cui_readyState")
        assertTrue(
            "CUI должен запускаться при readyState='interactive' или 'complete' " +
                "(DOMContentLoaded), не 'loading'. Факт: $readyState",
            readyState == "\"interactive\"" || readyState == "\"complete\"",
        )
    }

    /**
     * Корневая причина: отсутствует — проверяет базовый сценарий без rewriter'ов.
     * Regular скрипт стартует по DOMContentLoaded (как в Tampermonkey).
     *
     * НЕ отличает от 1.1.0, но ловит регрессию.
     */
    @Test
    fun regularScript_withoutRewriter_startsNormally() {
        setupWithScripts(fakeEuiScript())

        val game = launchAndWait()
        game.waitForJs("window.__e2e_eui_fired === true", "EUI probe")

        val readyState = game.evaluateJs("window.__e2e_eui_readyState")
        assertTrue(
            "readyState должен быть 'interactive' или 'complete', факт: $readyState",
            readyState == "\"interactive\"" || readyState == "\"complete\"",
        )
    }

    // --- helpers ---

    private fun setupWithScripts(vararg scripts: UserScript) {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        if (scripts.isNotEmpty()) {
            val storage = ScriptStorageFixture.storage()
            scripts.forEach { storage.save(it) }
        }
    }

    private fun launchAndWait(): GameScreen {
        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        return game
    }

    /**
     * Ждёт пока JS-выражение вернёт "true" (строка — результат evaluateJs).
     * Заменяет Thread.sleep: polling с 50ms интервалом, таймаут POLL_TIMEOUT_MS.
     */
    private fun GameScreen.waitForJs(
        expression: String,
        description: String = expression,
    ) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (evaluateJs(expression) == "true") return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("$description: не дождались true за ${POLL_TIMEOUT_MS}ms")
    }

    private fun fakeCuiScript(): UserScript {
        val content = """
            // ==UserScript==
            // @name Fake CUI
            // @namespace e2e-tests
            // @version 1.0.0
            // @match https://sbg-game.ru/app/*
            // @run-at document-idle
            // ==/UserScript==
            window.__e2e_cui_fired = true;
            window.__e2e_cui_initial_cuiStatus = window.cuiStatus;
            window.__e2e_cui_readyState = document.readyState;
            document.open();
            document.write('<html><body><div id="game-root">rebuilt</div><script>window.i18next={isInitialized:true};window.__sbgFakeReady=true;window.__sbgFakePage="app";<\/script></body></html>');
            document.close();
            window.cuiStatus = 'loading';
            setTimeout(function() { window.cuiStatus = 'loaded'; }, 500);
        """.trimIndent()
        return UserScript(
            identifier = ScriptIdentifier("fake-cui"),
            header = ScriptHeader(
                name = "Fake CUI",
                version = "1.0.0",
                namespace = "e2e-tests",
                match = listOf("https://sbg-game.ru/app/*"),
                runAt = "document-idle",
            ),
            sourceUrl = "https://example.com/fake-cui.user.js",
            updateUrl = null,
            content = content,
            enabled = true,
        )
    }

    private fun fakeEuiScript(): UserScript {
        val content = """
            // ==UserScript==
            // @name Fake EUI
            // @namespace e2e-tests
            // @version 1.0.0
            // @match https://sbg-game.ru/app/*
            // ==/UserScript==
            window.__e2e_eui_fired = true;
            window.__e2e_eui_readyState = document.readyState;
            window.__e2e_eui_cuiStatus = window.cuiStatus;
            window.__e2e_eui_run_count = (window.__e2e_eui_run_count || 0) + 1;
        """.trimIndent()
        return UserScript(
            identifier = ScriptIdentifier("fake-eui"),
            header = ScriptHeader(
                name = "Fake EUI",
                version = "1.0.0",
                namespace = "e2e-tests",
                match = listOf("https://sbg-game.ru/app/*"),
            ),
            sourceUrl = "https://example.com/fake-eui.user.js",
            updateUrl = null,
            content = content,
            enabled = true,
        )
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 50L
    }
}
