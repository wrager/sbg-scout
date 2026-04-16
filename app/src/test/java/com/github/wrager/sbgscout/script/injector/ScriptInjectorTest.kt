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

    @Test
    fun `wrapInSafeIife wraps content with DOMContentLoaded by default`() {
        val result = ScriptInjector.wrapInSafeIife("Test Script", "console.log('hello');")

        assertTrue(result.contains("(function() {"))
        assertTrue(result.contains("try {"))
        assertTrue(result.contains("console.log('hello');"))
        assertTrue(result.contains("} catch (error) {"))
        assertTrue(result.contains("DOMContentLoaded"))
        assertTrue(result.contains("document.readyState"))
        assertFalse(
            "Не должен использовать setTimeout — Scout не различает document-end и document-idle",
            result.contains("setTimeout"),
        )
        assertTrue(result.contains("})();"))
    }

    @Test
    fun `wrapInSafeIife with document-idle uses same DOMContentLoaded as default`() {
        val defaultResult = ScriptInjector.wrapInSafeIife("A", "code")
        val idleResult = ScriptInjector.wrapInSafeIife("A", "code", runAt = "document-idle")
        val endResult = ScriptInjector.wrapInSafeIife("A", "code", runAt = "document-end")

        assertEquals(
            "document-idle, document-end и default должны генерировать одинаковую обёртку",
            defaultResult,
            idleResult,
        )
        assertEquals(
            "document-end и default должны генерировать одинаковую обёртку",
            defaultResult,
            endResult,
        )
    }

    @Test
    fun `wrapInSafeIife with document-start injects immediately without DOMContentLoaded`() {
        val result = ScriptInjector.wrapInSafeIife(
            "Early Script",
            "console.log('early');",
            runAt = "document-start",
        )

        assertTrue(result.contains("console.log('early');"))
        assertTrue(result.contains("try {"))
        assertFalse(result.contains("DOMContentLoaded"))
    }

    @Test
    fun `wrapInSafeIife escapes single quotes in script name`() {
        val result = ScriptInjector.wrapInSafeIife("Script's Name", "code")

        assertTrue(result.contains("Script\\'s Name"))
    }

    @Test
    fun `wrapInSafeIife escapes backslashes in script name`() {
        val result = ScriptInjector.wrapInSafeIife("Script\\Path", "code")

        assertTrue(result.contains("Script\\\\Path"))
    }

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

    @Test
    fun `inject calls evaluateJavascript for globals and polyfill when no scripts`() {
        every { scriptStorage.getEnabled() } returns emptyList()

        injector.inject(webView)

        // document.write event fix + globals + polyfill = 3 вызова evaluateJavascript
        verify(exactly = 3) {
            webView.evaluateJavascript(any(), any<ValueCallback<String>>())
        }
    }

    @Test
    fun `inject calls evaluateJavascript for each enabled script plus error collection`() {
        every { scriptStorage.getEnabled() } returns listOf(
            createTestScript("script-a", "Script A", "code_a"),
            createTestScript("script-b", "Script B", "code_b"),
        )

        injector.inject(webView)

        // document.write event fix + globals + polyfill + 2 скрипта + 1 collectErrors = 6 вызовов
        verify(exactly = 6) {
            webView.evaluateJavascript(any(), any<ValueCallback<String>>())
        }
    }

    @Test
    fun `inject uses getEnabled not getAll`() {
        every { scriptStorage.getEnabled() } returns emptyList()

        injector.inject(webView)

        verify { scriptStorage.getEnabled() }
        verify(exactly = 0) { scriptStorage.getAll() }
    }

    @Test
    fun `inject injects globals containing application id and version`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        // [0] = document.write event fix, [1] = globals
        val globalsScript = capturedScripts[1]
        assertTrue(globalsScript.contains("window.__sbg_package = 'com.github.wrager.sbgscout'"))
        assertTrue(globalsScript.contains("window.__sbg_package_version = '1.0'"))
    }

    @Test
    fun `inject wraps each script in IIFE with try-catch and error collection`() {
        every { scriptStorage.getEnabled() } returns listOf(
            createTestScript("test/id", "My Script", "alert(1);"),
        )
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        // Четвёртый вызов — скрипт (после event fix, globals и polyfill)
        val scriptCall = capturedScripts[3]
        assertTrue(scriptCall.contains("(function() {"))
        assertTrue(scriptCall.contains("try {"))
        assertTrue(scriptCall.contains("alert(1);"))
        assertTrue(scriptCall.contains("} catch (error) {"))
        assertTrue(scriptCall.contains("window.__sbg_injection_errors"))
    }

    @Test
    fun `inject injects clipboard polyfill with navigator check`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        // [0] = document.write event fix, [1] = globals, [2] = polyfill
        val polyfillScript = capturedScripts[2]
        assertTrue(polyfillScript.contains("navigator.clipboard"))
        assertTrue(polyfillScript.contains("Android.readText()"))
        assertTrue(polyfillScript.contains("Android.writeText(text)"))
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

    @Test
    fun `inject callback receives empty list when no scripts enabled`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        var callbackResults: List<InjectionResult>? = null

        injector.inject(webView) { callbackResults = it }

        assertEquals(emptyList<InjectionResult>(), callbackResults)
    }

    @Test
    fun `inject injects document write event fix as first script`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        val fixScript = capturedScripts[0]
        assertTrue(
            "Фикс должен перехватывать addEventListener",
            fixScript.contains("EventTarget.prototype.addEventListener"),
        )
        assertTrue(
            "Фикс должен перехватывать document.write",
            fixScript.contains("Document.prototype.write"),
        )
        assertTrue(
            "Фикс должен перехватывать document.close",
            fixScript.contains("Document.prototype.close"),
        )
        assertTrue(
            "Фикс должен перехватывать dispatchEvent",
            fixScript.contains("window.dispatchEvent"),
        )
    }

    @Test
    fun `document write event fix saves Ready listeners for re-registration`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        val fixScript = capturedScripts[0]
        assertTrue(
            "Фикс должен сохранять listeners в массив",
            fixScript.contains("savedListeners.push"),
        )
        assertTrue(
            "Фикс должен фильтровать по паттерну Ready",
            fixScript.contains("Ready"),
        )
    }

    @Test
    fun `document write event fix re-registers listeners after document close`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        val fixScript = capturedScripts[0]
        // После document.close перерегистрируем listeners и re-dispatch события
        assertTrue(
            "Фикс должен перерегистрировать listeners через origAddEventListener",
            fixScript.contains("origAddEventListener.call(window, entry.type, entry.fn"),
        )
        assertTrue(
            "Фикс должен re-dispatch потерянных событий",
            fixScript.contains("Re-dispatching lost event"),
        )
    }

    @Test
    fun `document write event fix tracks listener calls during document write`() {
        every { scriptStorage.getEnabled() } returns emptyList()
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        val fixScript = capturedScripts[0]
        // Должен отслеживать insideDocWrite и listenersCalledDuringWrite
        assertTrue(
            "Фикс должен отслеживать состояние insideDocWrite",
            fixScript.contains("insideDocWrite"),
        )
        assertTrue(
            "Фикс должен трекать вызовы listeners во время document.write",
            fixScript.contains("listenersCalledDuringWrite"),
        )
    }

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
        val rewriter = createTestScript("rewriter", "Rewriter", "document.open(); document.write('<html></html>');")
        val another = createTestScript("another", "Another", "alert(1);")

        val sorted = ScriptInjector.sortByInjectionPriority(listOf(regular, rewriter, another))

        assertEquals("rewriter", sorted[0].identifier.value)
        assertEquals("regular", sorted[1].identifier.value)
        assertEquals("another", sorted[2].identifier.value)
    }

    @Test
    fun `sortByInjectionPriority preserves order when no document-open scripts`() {
        val scriptA = createTestScript("a", "A", "code_a")
        val scriptB = createTestScript("b", "B", "code_b")
        val scriptC = createTestScript("c", "C", "code_c")

        val sorted = ScriptInjector.sortByInjectionPriority(listOf(scriptA, scriptB, scriptC))

        assertEquals("a", sorted[0].identifier.value)
        assertEquals("b", sorted[1].identifier.value)
        assertEquals("c", sorted[2].identifier.value)
    }

    @Test
    fun `inject injects document-open script before regular scripts`() {
        val regular = createTestScript("regular", "Regular", "regular_code")
        val rewriter = createTestScript("rewriter", "Rewriter", "document.open(); rewriter_code")
        every { scriptStorage.getEnabled() } returns listOf(regular, rewriter)
        val capturedScripts = mutableListOf<String>()
        every {
            webView.evaluateJavascript(capture(capturedScripts), any<ValueCallback<String>>())
        } returns Unit

        injector.inject(webView)

        // [0] = event fix, [1] = globals, [2] = polyfill, [3] = rewriter, [4] = regular
        val rewriterIndex = capturedScripts.indexOfFirst { it.contains("rewriter_code") }
        val regularIndex = capturedScripts.indexOfFirst { it.contains("regular_code") }
        assertTrue(
            "Скрипт с document.open() должен инжектироваться раньше обычных",
            rewriterIndex < regularIndex,
        )
    }

    private fun createTestScript(
        identifier: String,
        name: String,
        content: String,
    ): UserScript = UserScript(
        identifier = ScriptIdentifier(identifier),
        header = ScriptHeader(name = name),
        sourceUrl = null,
        updateUrl = null,
        content = content,
        enabled = true,
    )
}
