package com.github.wrager.sbgscout.webview

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.ValueCallback
import android.widget.Toast
import com.github.wrager.sbgscout.bridge.DownloadBridge
import com.github.wrager.sbgscout.bridge.GameSettingsBridge
import com.github.wrager.sbgscout.bridge.ScoutBridge
import com.github.wrager.sbgscout.config.GameUrls
import com.github.wrager.sbgscout.script.injector.InjectionResult
import com.github.wrager.sbgscout.script.injector.ScriptInjector
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-тесты [SbgWebViewClient] — покрывают все ветки в [onPageStarted],
 * [onPageFinished], [shouldOverrideUrlLoading], [onRenderProcessGone],
 * setter'е [onGamePageFinished] с race-condition, [unescapeJsString] и
 * приватный [handleInjectionResults] через [onPageStarted] callback.
 */
class SbgWebViewClientTest {

    private lateinit var scriptInjector: ScriptInjector
    private lateinit var client: SbgWebViewClient

    @Before
    fun setUp() {
        // Override host для GameUrls.isGameAppPage чтобы тесты не зависели от
        // BuildConfig.GAME_HOST_MATCH (может быть "sbg-game.ru" или "127.0.0.1").
        GameUrls.hostMatchOverride = "test.host"

        scriptInjector = mockk()
        every { scriptInjector.inject(any(), any()) } just Runs

        client = SbgWebViewClient(scriptInjector)
        mockkStatic(Toast::class)
    }

    @After
    fun tearDown() {
        GameUrls.hostMatchOverride = null
        unmockkStatic(Toast::class)
    }

    // --- onPageStarted ---

    @Test
    fun `onPageStarted with game URL runs bootstrap scripts and injects`() {
        val webView = mockk<WebView>(relaxed = true)
        var startedCalled = false
        client.onGamePageStarted = { startedCalled = true }

        client.onPageStarted(webView, "http://test.host/app", null)

        // UA override обязан быть ПЕРВЫМ: EUI читает navigator.userAgent в
        // пользовательских скриптах, которые `scriptInjector.inject` запускает
        // после bootstrap'ов — к этому моменту геттер уже должен быть перехвачен.
        verifyOrder {
            webView.evaluateJavascript(UserAgentOverride.BOOTSTRAP_SCRIPT, any())
            webView.evaluateJavascript(DownloadBridge.BOOTSTRAP_SCRIPT, any())
            webView.evaluateJavascript(GameSettingsBridge.LOCAL_STORAGE_WRAPPER, any())
            webView.evaluateJavascript(ScoutBridge.BOOTSTRAP_SCRIPT, any())
            scriptInjector.inject(webView, any())
        }
        assertTrue(startedCalled)
    }

    @Test
    fun `onPageStarted with non-game URL does nothing`() {
        val webView = mockk<WebView>(relaxed = true)
        var startedCalled = false
        client.onGamePageStarted = { startedCalled = true }

        client.onPageStarted(webView, "http://other.host/app", null)

        verify(exactly = 0) { scriptInjector.inject(any(), any()) }
        assertFalse(startedCalled)
    }

    @Test
    fun `onPageStarted with null view does nothing`() {
        // Покрывает ветку `GameUrls.isGameAppPage(url) && view != null` — view=null.
        client.onPageStarted(null, "http://test.host/app", null)

        verify(exactly = 0) { scriptInjector.inject(any(), any()) }
    }

    @Test
    fun `onPageStarted without callback does not throw`() {
        // Покрывает ветку `onGamePageStarted?.invoke()` = null.
        val webView = mockk<WebView>(relaxed = true)
        client.onGamePageStarted = null

        client.onPageStarted(webView, "http://test.host/app", null)

        verify { scriptInjector.inject(webView, any()) }
    }

    // --- onPageFinished ---

    @Test
    fun `onPageFinished with game URL reads settings and fires callback`() {
        val webView = mockk<WebView>(relaxed = true)
        var settings: String? = "<unset>"
        var finishedCalled = false
        client.onGameSettingsRead = { settings = it }
        client.onGamePageFinished = { finishedCalled = true }

        // Мокаем evaluateJavascript("localStorage.getItem('settings')", cb) чтобы
        // подставить значение через cb.onReceiveValue("\"{\\\"a\\\":1}\"").
        val cbSlot = slot<ValueCallback<String>>()
        every {
            webView.evaluateJavascript("localStorage.getItem('settings')", capture(cbSlot))
        } answers {
            cbSlot.captured.onReceiveValue("\"{\\\"a\\\":1}\"")
        }

        client.onPageFinished(webView, "http://test.host/app")

        assertEquals("{\"a\":1}", settings)
        assertTrue(finishedCalled)
    }

    @Test
    fun `onPageFinished with non-game URL does nothing`() {
        val webView = mockk<WebView>(relaxed = true)
        var finishedCalled = false
        client.onGamePageFinished = { finishedCalled = true }

        client.onPageFinished(webView, "http://other.host/app")

        assertFalse(finishedCalled)
        verify(exactly = 0) { webView.evaluateJavascript(any<String>(), any()) }
    }

    @Test
    fun `onPageFinished with null settings callback does not call evaluateJavascript for settings`() {
        // Покрывает ветку `onGameSettingsRead?.let` = null.
        val webView = mockk<WebView>(relaxed = true)
        var finishedCalled = false
        client.onGameSettingsRead = null
        client.onGamePageFinished = { finishedCalled = true }

        client.onPageFinished(webView, "http://test.host/app")

        assertTrue(finishedCalled)
        verify(exactly = 0) { webView.evaluateJavascript("localStorage.getItem('settings')", any()) }
    }

    // --- onGamePageFinished setter race ---

    @Test
    fun `setting onGamePageFinished after first finish invokes callback immediately`() {
        // Setter idempotent: если gamePageFinishedAtLeastOnce = true к моменту
        // установки, callback вызывается сразу.
        val webView = mockk<WebView>(relaxed = true)
        client.onPageFinished(webView, "http://test.host/app")

        var lateCalled = false
        client.onGamePageFinished = { lateCalled = true }

        assertTrue(lateCalled)
    }

    @Test
    fun `setting onGamePageFinished to null before first finish does nothing`() {
        // Покрывает ветку `value?.invoke()` = null.
        client.onGamePageFinished = null
        // Не должно бросать NPE.
        assertTrue(true)
    }

    @Test
    fun `setting onGamePageFinished to null after first finish does not throw`() {
        // Покрывает ветку `value?.invoke()` = null при `gamePageFinishedAtLeastOnce=true`
        // (setter видит true-state и value=null — skip invocation).
        val webView = mockk<WebView>(relaxed = true)
        client.onPageFinished(webView, "http://test.host/app")

        client.onGamePageFinished = null
        assertTrue(true)
    }

    @Test
    fun `onPageFinished with null view does nothing`() {
        // Покрывает ветку `isGameAppPage(url) && view != null` — view=null.
        var finishedCalled = false
        client.onGamePageFinished = { finishedCalled = true }

        client.onPageFinished(null, "http://test.host/app")

        assertFalse(finishedCalled)
    }

    @Test
    fun `shouldOverrideUrlLoading returns false when request url is null`() {
        // Покрывает ветку `request?.url?.toString() ?: return false` — request != null,
        // но request.url = null.
        val request = mockk<WebResourceRequest>()
        every { request.url } returns null

        assertFalse(client.shouldOverrideUrlLoading(null, request))
    }

    // --- handleInjectionResults (через onPageStarted → inject callback) ---

    @Test
    fun `handleInjectionResults shows Toast when there are script errors`() {
        val webView = mockk<WebView>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { webView.context } returns context
        every { context.getString(any(), any()) } returns "script error message"
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any<Context>(), any<CharSequence>(), any()) } returns toast

        // Подготавливаем scriptInjector чтобы сразу вызвать callback с errors.
        val resultsCallback = slot<(List<InjectionResult>) -> Unit>()
        every { scriptInjector.inject(webView, capture(resultsCallback)) } answers {
            resultsCallback.captured(
                listOf(
                    InjectionResult.ScriptError(
                        identifier = ScriptIdentifier("test/script"),
                        scriptName = "Test Script",
                        errorMessage = "oops",
                    ),
                ),
            )
        }

        client.onPageStarted(webView, "http://test.host/app", null)

        verify { Toast.makeText(context, any<CharSequence>(), Toast.LENGTH_LONG) }
        verify { toast.show() }
    }

    @Test
    fun `handleInjectionResults uses identifier when scriptName is blank`() {
        val webView = mockk<WebView>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { webView.context } returns context
        val nameSlot = slot<Any>()
        every { context.getString(any(), capture(nameSlot)) } returns "script error"
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any<Context>(), any<CharSequence>(), any()) } returns toast

        val resultsCallback = slot<(List<InjectionResult>) -> Unit>()
        every { scriptInjector.inject(webView, capture(resultsCallback)) } answers {
            resultsCallback.captured(
                listOf(
                    InjectionResult.ScriptError(
                        identifier = ScriptIdentifier("fallback/identifier"),
                        scriptName = "",
                        errorMessage = "err",
                    ),
                ),
            )
        }

        client.onPageStarted(webView, "http://test.host/app", null)

        // В captured имя берётся из identifier, т.к. scriptName is blank
        assertEquals("fallback/identifier", nameSlot.captured.toString())
    }

    @Test
    fun `handleInjectionResults does nothing when no errors`() {
        val webView = mockk<WebView>(relaxed = true)
        val resultsCallback = slot<(List<InjectionResult>) -> Unit>()
        every { scriptInjector.inject(webView, capture(resultsCallback)) } answers {
            resultsCallback.captured(
                listOf(
                    InjectionResult.Success(ScriptIdentifier("ok/script")),
                ),
            )
        }

        client.onPageStarted(webView, "http://test.host/app", null)

        verify(exactly = 0) { Toast.makeText(any<Context>(), any<CharSequence>(), any()) }
    }

    // --- onRenderProcessGone ---

    @Test
    fun `onRenderProcessGone returns false`() {
        val detail = mockk<RenderProcessGoneDetail>(relaxed = true)
        assertFalse(client.onRenderProcessGone(null, detail))
    }

    // --- shouldOverrideUrlLoading ---

    @Test
    fun `shouldOverrideUrlLoading returns false for null request`() {
        assertFalse(client.shouldOverrideUrlLoading(null, null as WebResourceRequest?))
    }

    @Test
    fun `shouldOverrideUrlLoading returns false for regular URL`() {
        val request = mockk<WebResourceRequest>()
        val uri = mockk<Uri>()
        every { request.url } returns uri
        every { uri.toString() } returns "https://example.com/page"

        assertFalse(client.shouldOverrideUrlLoading(null, request))
    }

    @Test
    fun `shouldOverrideUrlLoading finishes activity and returns true for window close URL`() {
        val activity = mockk<Activity>(relaxed = true)
        val webView = mockk<WebView>()
        every { webView.context } returns activity

        val request = mockk<WebResourceRequest>()
        val uri = mockk<Uri>()
        every { request.url } returns uri
        every { uri.toString() } returns "https://example.com/?window.close=true"

        assertTrue(client.shouldOverrideUrlLoading(webView, request))
        verify { activity.finish() }
    }

    @Test
    fun `shouldOverrideUrlLoading handles window close with non-Activity context`() {
        // Покрывает ветку `context is Activity` = false.
        val nonActivityContext = mockk<Context>()
        val webView = mockk<WebView>()
        every { webView.context } returns nonActivityContext

        val request = mockk<WebResourceRequest>()
        val uri = mockk<Uri>()
        every { request.url } returns uri
        every { uri.toString() } returns "https://example.com/window.close"

        // Не бросает исключение, возвращает true (так как URL содержит window.close)
        assertTrue(client.shouldOverrideUrlLoading(webView, request))
    }

    @Test
    fun `shouldOverrideUrlLoading handles window close with null view`() {
        // Покрывает ветку view = null при window.close URL.
        val request = mockk<WebResourceRequest>()
        val uri = mockk<Uri>()
        every { request.url } returns uri
        every { uri.toString() } returns "https://example.com/window.close"

        assertTrue(client.shouldOverrideUrlLoading(null, request))
    }

    // --- unescapeJsString ---

    @Test
    fun `unescapeJsString returns null for null input`() {
        assertNull(SbgWebViewClient.unescapeJsString(null))
    }

    @Test
    fun `unescapeJsString returns null for blank input`() {
        assertNull(SbgWebViewClient.unescapeJsString(""))
        assertNull(SbgWebViewClient.unescapeJsString("   "))
    }

    @Test
    fun `unescapeJsString returns null for literal null string`() {
        assertNull(SbgWebViewClient.unescapeJsString("null"))
    }

    @Test
    fun `unescapeJsString removes surrounding quotes and unescapes internal quotes`() {
        assertEquals(
            """{"key":"value"}""",
            SbgWebViewClient.unescapeJsString("""  "{\"key\":\"value\"}"  """.trim()),
        )
    }

    @Test
    fun `unescapeJsString unescapes backslashes`() {
        assertEquals(
            """a\b""",
            SbgWebViewClient.unescapeJsString(""""a\\b""""),
        )
    }

    @Test
    fun `unescapeJsString returns null when unescaped result is literal null`() {
        // Покрывает ветку takeIf { it != "null" } — если после removeSurrounding
        // и unescape остался литерал "null", возвращаем null.
        assertNull(SbgWebViewClient.unescapeJsString("\"null\""))
    }
}
