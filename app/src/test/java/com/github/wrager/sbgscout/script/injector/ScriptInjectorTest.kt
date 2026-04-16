package com.github.wrager.sbgscout.script.injector

import android.webkit.ValueCallback
import android.webkit.WebView
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptInjectorTest {

    private lateinit var scriptStorage: ScriptStorage
    private lateinit var webView: WebView
    private lateinit var injector: ScriptInjector

    @Before
    fun setUp() {
        scriptStorage = mockk()
        webView = mockk(relaxed = true)
        injector = ScriptInjector(
            scriptStorage = scriptStorage,
            applicationId = "com.github.wrager.sbgscout",
            versionName = "1.0",
        )
    }

    // --- wrapInTryCatch ---

    @Test
    fun `wrapInTryCatch wraps content in IIFE with try-catch`() {
        val result = ScriptInjector.wrapInTryCatch("Test Script", "console.log('hello');")

        assertTrue(result.contains("(function() {"))
        assertTrue(result.contains("try {"))
        assertTrue(result.contains("console.log('hello');"))
        assertTrue(result.contains("} catch (error) {"))
        assertTrue(result.contains("window.__sbg_injection_errors"))
        assertTrue(result.contains("})();"))
    }

    @Test
    fun `wrapInTryCatch does not include timing logic`() {
        val result = ScriptInjector.wrapInTryCatch("Test", "code")

        assertFalse(result.contains("DOMContentLoaded"))
        assertFalse(result.contains("readyState"))
        assertFalse(result.contains("setTimeout"))
    }

    @Test
    fun `wrapInTryCatch escapes single quotes in script name`() {
        val result = ScriptInjector.wrapInTryCatch("Script's Name", "code")

        assertTrue(result.contains("Script\\'s Name"))
    }

    @Test
    fun `wrapInTryCatch escapes backslashes in script name`() {
        val result = ScriptInjector.wrapInTryCatch("Script\\Path", "code")

        assertTrue(result.contains("Script\\\\Path"))
    }

    // --- buildDeferredBatch ---

    @Test
    fun `buildDeferredBatch wraps scripts in single DOMContentLoaded handler`() {
        val scripts = listOf(
            createTestScript("a", "Script A", "code_a"),
            createTestScript("b", "Script B", "code_b"),
        )

        val result = ScriptInjector.buildDeferredBatch(scripts)

        assertTrue(result.contains("DOMContentLoaded"))
        assertTrue(result.contains("document.readyState === 'loading'"))
        assertTrue(result.contains("code_a"))
        assertTrue(result.contains("code_b"))
        // Один runAll вызов, не отдельные DOMContentLoaded listeners
        assertEquals(
            "Должен быть ровно один DOMContentLoaded listener",
            1,
            result.windowed("DOMContentLoaded".length)
                .count { it == "DOMContentLoaded" },
        )
    }

    @Test
    fun `buildDeferredBatch preserves script order`() {
        val scripts = listOf(
            createTestScript("first", "First", "first_code"),
            createTestScript("second", "Second", "second_code"),
            createTestScript("third", "Third", "third_code"),
        )

        val result = ScriptInjector.buildDeferredBatch(scripts)

        val firstIndex = result.indexOf("first_code")
        val secondIndex = result.indexOf("second_code")
        val thirdIndex = result.indexOf("third_code")
        assertTrue(firstIndex < secondIndex)
        assertTrue(secondIndex < thirdIndex)
    }

    @Test
    fun `buildDeferredBatch sets cuiStatus before document-open script`() {
        val rewriter = createTestScript(
            "rewriter",
            "Rewriter",
            "document.open(); rewrite_code",
        )
        val regular = createTestScript("regular", "Regular", "regular_code")

        val result = ScriptInjector.buildDeferredBatch(listOf(rewriter, regular))

        assertTrue(
            "cuiStatus='initializing' должен быть перед document.open() скриптом",
            result.contains("window.cuiStatus = 'initializing'"),
        )
        assertTrue(
            "cuiStatus должен быть раньше кода скрипта с document.open()",
            result.indexOf("cuiStatus") < result.indexOf("rewrite_code"),
        )
    }

    @Test
    fun `buildDeferredBatch does not set cuiStatus before regular script`() {
        val regular = createTestScript("regular", "Regular", "regular_code")

        val result = ScriptInjector.buildDeferredBatch(listOf(regular))

        assertFalse(
            "cuiStatus не должен выставляться для обычных скриптов",
            result.contains("cuiStatus"),
        )
    }

    @Test
    fun `buildDeferredBatch isolates errors per script`() {
        val scripts = listOf(
            createTestScript("a", "A", "code_a"),
            createTestScript("b", "B", "code_b"),
        )

        val result = ScriptInjector.buildDeferredBatch(scripts)

        // Каждый скрипт в своём try-catch
        assertEquals(
            "Каждый скрипт должен быть в отдельном try-catch",
            2,
            result.windowed("try {".length).count { it == "try {" },
        )
    }

    // --- buildImmediateBatch ---

    @Test
    fun `buildImmediateBatch does not include DOMContentLoaded`() {
        val scripts = listOf(createTestScript("a", "A", "code_a"))

        val result = ScriptInjector.buildImmediateBatch(scripts)

        assertFalse(result.contains("DOMContentLoaded"))
        assertFalse(result.contains("readyState"))
        assertTrue(result.contains("code_a"))
        assertTrue(result.contains("try {"))
    }

    // --- inject: single evaluateJavascript ---

    @Test
    fun `inject uses single evaluateJavascript when no scripts`() {
        every { scriptStorage.getEnabled() } returns emptyList()

        injector.inject(webView)

        // Один payload (preamble only), без collectErrors
        verify(exactly = 1) {
            webView.evaluateJavascript(any(), any<ValueCallback<String>>())
        }
    }

    @Test
    fun `inject uses single evaluateJavascript plus collectErrors with scripts`() {
        every { scriptStorage.getEnabled() } returns listOf(
            createTestScript("a", "A", "code_a"),
            createTestScript("b", "B", "code_b"),
        )

        injector.inject(webView)

        // 1 payload + 1 collectErrors = 2
        verify(exactly = 2) {
            webView.evaluateJavascript(any(), any<ValueCallback<String>>())
        }
    }

    // --- buildInjectionPayload ---

    @Test
    fun `buildInjectionPayload contains preamble and deferred batch`() {
        val scripts = listOf(
            createTestScript("a", "A", "code_a"),
            createTestScript("b", "B", "code_b"),
        )

        val payload = injector.buildInjectionPayload(scripts)

        // Preamble
        assertTrue("Должен содержать event fix", payload.contains("EventTarget.prototype.addEventListener"))
        assertTrue("Должен содержать globals", payload.contains("window.__sbg_package"))
        assertTrue("Должен содержать polyfill", payload.contains("navigator.clipboard"))
        // Скрипты в deferred batch
        assertTrue("Должен содержать первый скрипт", payload.contains("code_a"))
        assertTrue("Должен содержать второй скрипт", payload.contains("code_b"))
        assertTrue("Deferred batch должен использовать DOMContentLoaded", payload.contains("DOMContentLoaded"))
    }

    @Test
    fun `inject sorts document-open script before others in payload`() {
        val regular = createTestScript("regular", "Regular", "regular_code")
        val rewriter = createTestScript(
            "rewriter",
            "Rewriter",
            "document.open(); rewriter_code",
        )
        // getEnabled возвращает в «неправильном» порядке
        every { scriptStorage.getEnabled() } returns listOf(regular, rewriter)
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        val payload = capturedScripts[0]
        assertTrue(
            "document.open() скрипт должен быть раньше обычного в payload",
            payload.indexOf("rewriter_code") < payload.indexOf("regular_code"),
        )
    }

    @Test
    fun `buildInjectionPayload separates document-start and deferred scripts`() {
        val scripts = listOf(
            createTestScript("early", "Early", "early_code", runAt = "document-start"),
            createTestScript("deferred", "Deferred", "deferred_code"),
        )

        val payload = injector.buildInjectionPayload(scripts)

        assertTrue(payload.contains("early_code"))
        assertTrue(payload.contains("deferred_code"))
        // document-start идёт раньше deferred batch (ищем runAll — маркер deferred batch)
        assertTrue(payload.indexOf("early_code") < payload.indexOf("runAll"))
    }

    @Test
    fun `buildInjectionPayload preamble only when no scripts`() {
        val payload = injector.buildInjectionPayload(emptyList())

        assertTrue(payload.contains("EventTarget.prototype.addEventListener"))
        assertTrue(payload.contains("window.__sbg_package"))
        assertTrue(payload.contains("navigator.clipboard"))
        // Без скриптов не должно быть runAll (deferred batch)
        assertFalse("Не должно быть deferred batch без скриптов", payload.contains("runAll"))
    }

    @Test
    fun `inject uses getEnabled not getAll`() {
        every { scriptStorage.getEnabled() } returns emptyList()

        injector.inject(webView)

        verify { scriptStorage.getEnabled() }
        verify(exactly = 0) { scriptStorage.getAll() }
    }

    @Test
    fun `inject does not include disabled scripts`() {
        val enabledScript = createTestScript("enabled", "Enabled", "enabled_code")
        every { scriptStorage.getEnabled() } returns listOf(enabledScript)
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        val allInjected = capturedScripts.joinToString()
        assertTrue(allInjected.contains("enabled_code"))
        assertFalse(allInjected.contains("disabled_code"))
    }

    @Test
    fun `inject callback receives empty list when no scripts enabled`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        var callbackResults: List<InjectionResult>? = null

        injector.inject(webView) { callbackResults = it }

        assertEquals(emptyList<InjectionResult>(), callbackResults)
    }

    // --- buildGlobalVariablesScript ---

    @Test
    fun `buildGlobalVariablesScript sets all four variables`() {
        val result = ScriptInjector.buildGlobalVariablesScript(
            "com.example.app",
            "2.0.1",
        )

        assertTrue(result.contains("window.__sbg_local = false;"))
        assertTrue(result.contains("window.__sbg_preset = '';"))
        assertTrue(result.contains("window.__sbg_package = 'com.example.app';"))
        assertTrue(result.contains("window.__sbg_package_version = '2.0.1';"))
    }

    @Test
    fun `buildGlobalVariablesScript escapes quotes in values`() {
        val result = ScriptInjector.buildGlobalVariablesScript(
            "com.app'test",
            "1.0'beta",
        )

        assertTrue(result.contains("com.app\\'test"))
        assertTrue(result.contains("1.0\\'beta"))
    }

    // --- buildResults ---

    @Test
    fun `buildResults returns Success for all scripts when no errors`() {
        val scripts = listOf(
            createTestScript("id-a", "Script A", "code"),
            createTestScript("id-b", "Script B", "code"),
        )

        val results = ScriptInjector.buildResults(scripts, "\"[]\"")

        assertTrue(results.all { it is InjectionResult.Success })
        assertEquals(2, results.size)
    }

    @Test
    fun `buildResults returns ScriptError when error array contains entries`() {
        val scripts = listOf(
            createTestScript("id-a", "Script A", "code"),
            createTestScript("id-b", "Script B", "code"),
        )
        val errorsJson =
            "\"[{\\\"script\\\":\\\"Script A\\\"," +
                "\\\"error\\\":\\\"ReferenceError: x is not defined\\\"}]\""

        val results = ScriptInjector.buildResults(scripts, errorsJson)

        val errorResult = results
            .filterIsInstance<InjectionResult.ScriptError>()
            .first()
        assertEquals(ScriptIdentifier("id-a"), errorResult.identifier)
        assertEquals("Script A", errorResult.scriptName)
        assertEquals("ReferenceError: x is not defined", errorResult.errorMessage)

        val successResult = results
            .filterIsInstance<InjectionResult.Success>()
            .first()
        assertEquals(ScriptIdentifier("id-b"), successResult.identifier)
    }

    @Test
    fun `buildResults returns all Success when errors json is null`() {
        val scripts = listOf(createTestScript("id", "Script", "code"))

        val results = ScriptInjector.buildResults(scripts, null)

        assertEquals(1, results.size)
        assertTrue(results[0] is InjectionResult.Success)
    }

    @Test
    fun `buildResults returns all Success when errors json is malformed`() {
        val scripts = listOf(createTestScript("id", "Script", "code"))

        val results = ScriptInjector.buildResults(scripts, "not json")

        assertEquals(1, results.size)
        assertTrue(results[0] is InjectionResult.Success)
    }

    // --- DOCUMENT_WRITE_EVENT_FIX: DOMContentLoaded dedup ---

    @Test
    fun `event fix registers DOMContentLoaded wrappedFn with once true`() {
        val payload = injector.buildInjectionPayload(emptyList())

        // DOMContentLoaded listener должен регистрироваться с once: true
        // чтобы автоматически сняться после первого вызова (предотвращает
        // двойную инициализацию EUI на Chrome 146+ где document.write
        // не уничтожает window listeners)
        assertTrue(
            "Event fix должен содержать once: true для DOMContentLoaded",
            payload.contains("once: true"),
        )
        assertTrue(
            "Event fix должен проверять type === 'DOMContentLoaded' для once",
            payload.contains("type === 'DOMContentLoaded'"),
        )
    }

    @Test
    fun `event fix uses invoked flag to prevent double callback invocation`() {
        val payload = injector.buildInjectionPayload(emptyList())

        // entry.invoked guard предотвращает двойной вызов fn:
        // один раз из wrappedFn (естественный DOMContentLoaded), другой
        // из кеша в document.close()
        assertTrue(
            "Event fix должен инициализировать invoked=false",
            payload.contains("invoked: false"),
        )
        assertTrue(
            "Event fix wrappedFn должен проверять entry.invoked",
            payload.contains("if (entry.invoked) return"),
        )
        assertTrue(
            "Event fix wrappedFn должен ставить entry.invoked=true",
            payload.contains("entry.invoked = true"),
        )
    }

    @Test
    fun `event fix restores original prototypes on JS reload`() {
        val payload = injector.buildInjectionPayload(emptyList())

        assertTrue(
            "Event fix должен проверять наличие предыдущих оригиналов",
            payload.contains("window.__sbg_event_fix_originals"),
        )
        assertTrue(
            "Event fix должен восстанавливать addEventListener",
            payload.contains("EventTarget.prototype.addEventListener = orig.addEventListener"),
        )
        assertTrue(
            "Event fix должен сохранять оригиналы для будущих reload",
            payload.contains("addEventListener: origAddEventListener"),
        )
    }

    @Test
    fun `event fix document close invokes DOMContentLoaded only if not already invoked`() {
        val payload = injector.buildInjectionPayload(emptyList())

        // document.close() должен проверять entry.invoked перед вызовом fn
        assertTrue(
            "document.close должен фильтровать DOMContentLoaded listeners",
            payload.contains("entry.type === 'DOMContentLoaded'"),
        )
        assertTrue(
            "document.close должен проверять readyState перед вызовом cached callback",
            payload.contains("document.readyState !== 'loading'"),
        )
    }

    // --- EVENT_PRESERVE_PATTERN ---

    @Test
    fun `EVENT_PRESERVE_PATTERN matches Ready events and DOMContentLoaded`() {
        val pattern = Regex(ScriptInjector.EVENT_PRESERVE_PATTERN, RegexOption.IGNORE_CASE)

        assertTrue("dbReady", pattern.containsMatchIn("dbReady"))
        assertTrue("olReady", pattern.containsMatchIn("olReady"))
        assertTrue("mapReady", pattern.containsMatchIn("mapReady"))
        assertTrue("DOMContentLoaded", pattern.containsMatchIn("DOMContentLoaded"))
    }

    @Test
    fun `EVENT_PRESERVE_PATTERN does not match unrelated events`() {
        val pattern = Regex(ScriptInjector.EVENT_PRESERVE_PATTERN, RegexOption.IGNORE_CASE)

        assertFalse("click", pattern.containsMatchIn("click"))
        assertFalse("touchmove", pattern.containsMatchIn("touchmove"))
        assertFalse("load", pattern.containsMatchIn("load"))
        assertFalse("resize", pattern.containsMatchIn("resize"))
    }

    // --- rewritesDocument / sortByInjectionPriority ---

    @Test
    fun `rewritesDocument detects document open call`() {
        assertTrue(ScriptInjector.rewritesDocument("document.open();"))
        assertTrue(ScriptInjector.rewritesDocument("document.open()"))
        assertTrue(ScriptInjector.rewritesDocument("document .open()"))
        assertTrue(ScriptInjector.rewritesDocument("document. open ()"))
        assertTrue(ScriptInjector.rewritesDocument("document  .  open  (  )"))
        assertTrue(ScriptInjector.rewritesDocument("x = 1;\ndocument.open();\ny = 2;"))
    }

    @Test
    fun `rewritesDocument returns false for scripts without document open`() {
        assertFalse(ScriptInjector.rewritesDocument("console.log('hello');"))
        assertFalse(ScriptInjector.rewritesDocument("window.open()"))
        assertFalse(ScriptInjector.rewritesDocument(""))
    }

    @Test
    fun `sortByInjectionPriority puts document-open scripts first`() {
        val regular = createTestScript("regular", "Regular", "console.log('hi');")
        val rewriter = createTestScript(
            "rewriter",
            "Rewriter",
            "document.open(); document.write('<html></html>');",
        )
        val another = createTestScript("another", "Another", "alert(1);")

        val sorted = ScriptInjector.sortByInjectionPriority(
            listOf(regular, rewriter, another),
        )

        assertEquals("rewriter", sorted[0].identifier.value)
        assertEquals("regular", sorted[1].identifier.value)
        assertEquals("another", sorted[2].identifier.value)
    }

    @Test
    fun `sortByInjectionPriority preserves order when no document-open scripts`() {
        val scriptA = createTestScript("a", "A", "code_a")
        val scriptB = createTestScript("b", "B", "code_b")
        val scriptC = createTestScript("c", "C", "code_c")

        val sorted = ScriptInjector.sortByInjectionPriority(
            listOf(scriptA, scriptB, scriptC),
        )

        assertEquals("a", sorted[0].identifier.value)
        assertEquals("b", sorted[1].identifier.value)
        assertEquals("c", sorted[2].identifier.value)
    }

    // --- helpers ---

    private fun createTestScript(
        identifier: String,
        name: String,
        content: String,
        runAt: String? = null,
    ): UserScript = UserScript(
        identifier = ScriptIdentifier(identifier),
        header = ScriptHeader(name = name, runAt = runAt),
        sourceUrl = null,
        updateUrl = null,
        content = content,
        enabled = true,
    )
}
