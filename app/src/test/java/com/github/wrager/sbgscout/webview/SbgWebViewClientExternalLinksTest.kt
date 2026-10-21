package com.github.wrager.sbgscout.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.config.GameUrls
import io.mockk.EqMatcher
import io.mockk.Matcher
import io.mockk.MockKMatcherScope
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URISyntaxException
import java.util.IdentityHashMap

/**
 * Unit-тесты внешних ссылок [SbgWebViewClient]: какие переходы
 * [SbgWebViewClient.shouldOverrideUrlLoading] оставляет в WebView, а какие
 * и как открывает системой, и запасной путь в [SbgWebViewClient.onReceivedError].
 * Остальные ветки клиента — в [SbgWebViewClientTest].
 *
 * На JVM у `android.net.Uri` и `Intent` нет реализации (заглушки android.jar
 * возвращают null, 0, false), поэтому `Uri.parse`, `Intent.parseUri` и
 * конструктор `Intent` мокаются. Незастабленные вызовы идут в заглушки.
 */
class SbgWebViewClientExternalLinksTest {
    private lateinit var client: SbgWebViewClient

    /** Один matcher на каждый Uri, см. [SameInstanceMatcher]. */
    private val uriMatchers = IdentityHashMap<Uri, Matcher<Any>>()

    @Before
    fun setUp() {
        // Хост игры независимо от BuildConfig.
        GameUrls.hostMatchOverride = GAME_HOST
        mockkStatic(Uri::class)
        mockkStatic(Intent::class)
        mockkConstructor(Intent::class)
        mockkStatic(Toast::class)
        client = SbgWebViewClient(mockk(), PageLoadingTracker {})
    }

    @After
    fun tearDown() {
        GameUrls.hostMatchOverride = null
        GameUrls.betaServerEnabled = false
        unmockkStatic(Toast::class)
        unmockkConstructor(Intent::class)
        unmockkStatic(Intent::class)
        unmockkStatic(Uri::class)
    }

    /**
     * Matcher «тот же объект» для аргумента конструктора `Intent`. С [EqMatcher]
     * для мока Uri `constructedWith` в разных every/verify попадает к разным
     * представителям: equals и hashCode EqMatcher обращаются к моку, а внутри
     * этих блоков MockK записывает вызовы моков. У этого matcher'а equals и
     * hashCode собственные, и один экземпляр на Uri всегда даёт одного и того же
     * представителя.
     */
    private class SameInstanceMatcher(
        private val expected: Any,
    ) : Matcher<Any> {
        override fun match(arg: Any?): Boolean = arg === expected
    }

    /** Представитель всех `Intent(ACTION_VIEW, uri)`, созданных клиентом. */
    private fun MockKMatcherScope.viewIntent(uri: Uri): Intent =
        constructedWith<Intent>(
            EqMatcher(Intent.ACTION_VIEW),
            uriMatchers.getOrPut(uri) { SameInstanceMatcher(uri) },
        )

    /**
     * У `Intent` на JVM нет состояния: getData() отвечает только у
     * `Intent(ACTION_VIEW, uri)`, так по запущенному intent'у видно, как он создан.
     */
    private fun stubViewIntent(uri: Uri) {
        every { viewIntent(uri).data } returns uri
    }

    private fun assertViewIntent(
        uri: Uri,
        intent: Intent,
    ) {
        assertSame("ожидался Intent(ACTION_VIEW, $uri)", uri, intent.data)
    }

    private fun stubUri(
        url: String,
        scheme: String?,
        host: String? = null,
        path: String? = null,
    ): Uri {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.path } returns path
        every { uri.toString() } returns url
        every { Uri.parse(url) } returns uri
        return uri
    }

    private fun stubBankUri(): Uri =
        stubUri(
            "bank100000000004://Main/tpay/payment-1",
            scheme = "bank100000000004",
            host = "Main",
            path = "/tpay/payment-1",
        )

    private fun request(
        uri: Uri,
        isForMainFrame: Boolean = true,
    ): WebResourceRequest {
        val request = mockk<WebResourceRequest>()
        every { request.url } returns uri
        every { request.isForMainFrame } returns isForMainFrame
        return request
    }

    private fun webViewWith(context: Context): WebView {
        val webView = mockk<WebView>(relaxed = true)
        every { webView.context } returns context
        return webView
    }

    /** Activity, которая складывает запущенные intent'ы в [started]. */
    private fun activityRecording(started: MutableList<Intent>): Activity {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.startActivity(capture(started)) } just Runs
        return activity
    }

    private fun stubToast(): Toast {
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any<Context>(), any<Int>(), any()) } returns toast
        return toast
    }

    private fun stubParsedIntent(intentUrl: String): Intent {
        val parsedIntent = mockk<Intent>(relaxed = true)
        every { Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME) } returns parsedIntent
        return parsedIntent
    }

    private fun intentRequest(intentUrl: String): WebResourceRequest =
        request(stubUri(intentUrl, scheme = "intent", host = "pay", path = "/"))

    private fun verifyNotFoundToast(
        context: Context,
        toast: Toast,
    ) {
        verify { Toast.makeText(context, R.string.external_app_not_found, Toast.LENGTH_LONG) }
        verify { toast.show() }
    }

    // --- shouldOverrideUrlLoading: что остаётся в WebView ---

    @Test
    fun `keeps internal schemes in WebView`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWith(activityRecording(started))
        val urls =
            mapOf(
                "about:blank" to "about",
                "data:text/html,<p>page</p>" to "data",
                "blob:https://$GAME_HOST/0b6f2c4e" to "blob",
                "javascript:void(0)" to "javascript",
                "file:///android_asset/page.html" to "file",
            )

        for ((url, scheme) in urls) {
            assertFalse(url, client.shouldOverrideUrlLoading(webView, request(stubUri(url, scheme))))
        }
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps web pages of subframe in WebView`() {
        // Во вложенном фрейме даже магазин и Робокасса остаются в WebView.
        val started = mutableListOf<Intent>()
        val webView = webViewWith(activityRecording(started))
        val store = stubUri("https://$GAME_HOST/store/", scheme = "https", host = GAME_HOST, path = "/store/")
        val robokassa =
            stubUri(
                "https://auth.robokassa.ru/Merchant/Index.aspx",
                scheme = "https",
                host = "auth.robokassa.ru",
                path = "/Merchant/Index.aspx",
            )

        assertFalse(client.shouldOverrideUrlLoading(webView, request(store, isForMainFrame = false)))
        assertFalse(client.shouldOverrideUrlLoading(webView, request(robokassa, isForMainFrame = false)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps other game pages in WebView`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWith(activityRecording(started))
        val pages =
            listOf(
                stubUri("https://$GAME_HOST/rules/game/", scheme = "https", host = GAME_HOST, path = "/rules/game/"),
                stubUri("https://$GAME_HOST/login/", scheme = "https", host = GAME_HOST, path = "/login/"),
                stubUri("https://$GAME_HOST/storefront", scheme = "https", host = GAME_HOST, path = "/storefront"),
            )

        for (page in pages) {
            assertFalse(page.toString(), client.shouldOverrideUrlLoading(webView, request(page)))
        }
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps store path of foreign host in WebView`() {
        val started = mutableListOf<Intent>()
        val uri = stubUri("https://example.com/store/", scheme = "https", host = "example.com", path = "/store/")

        assertFalse(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), request(uri)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps Telegram OAuth in WebView`() {
        // Вход в игру через Telegram проходит в WebView и возвращается на /login/.
        val started = mutableListOf<Intent>()
        val uri =
            stubUri(
                "https://oauth.telegram.org/auth?bot_id=1&origin=https%3A%2F%2F$GAME_HOST",
                scheme = "https",
                host = "oauth.telegram.org",
                path = "/auth",
            )

        assertFalse(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), request(uri)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps lookalike hosts in WebView`() {
        // Совпадение по окончанию хоста считается только для поддоменов.
        val started = mutableListOf<Intent>()
        val webView = webViewWith(activityRecording(started))
        val lookalikes =
            listOf(
                stubUri("https://notrobokassa.ru/", scheme = "https", host = "notrobokassa.ru", path = "/"),
                stubUri("https://at.me/", scheme = "https", host = "at.me", path = "/"),
                stubUri("https://t.me.example.com/", scheme = "https", host = "t.me.example.com", path = "/"),
            )

        for (uri in lookalikes) {
            assertFalse(uri.toString(), client.shouldOverrideUrlLoading(webView, request(uri)))
        }
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps web page without host in WebView`() {
        val started = mutableListOf<Intent>()
        val uri = stubUri("https:///page", scheme = "https", host = null, path = "/page")

        assertFalse(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), request(uri)))
        assertTrue(started.isEmpty())
    }

    // --- shouldOverrideUrlLoading: что открывается системой ---

    @Test
    fun `opens app link of main frame externally`() {
        // Оплата через СБП: Робокасса ведёт на ссылку приложения банка.
        val started = mutableListOf<Intent>()
        val bankUri = stubBankUri()
        stubViewIntent(bankUri)

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), request(bankUri)))

        assertViewIntent(bankUri, started.single())
        // NEW_TASK не нужен, контекст — Activity.
        verify(exactly = 0) { viewIntent(bankUri).selector = any() }
        verify(exactly = 0) { viewIntent(bankUri).addFlags(any()) }
    }

    @Test
    fun `opens app link of subframe externally`() {
        val started = mutableListOf<Intent>()
        val bankUri = stubBankUri()
        stubViewIntent(bankUri)

        val handled =
            client.shouldOverrideUrlLoading(
                webViewWith(activityRecording(started)),
                request(bankUri, isForMainFrame = false),
            )

        assertTrue(handled)
        assertViewIntent(bankUri, started.single())
    }

    @Test
    fun `opens other app schemes externally`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWith(activityRecording(started))
        val links =
            listOf(
                stubUri("tel:+79990000000", scheme = "tel"),
                stubUri("mailto:support@example.com", scheme = "mailto"),
                stubUri("tg://resolve?domain=sbg_game", scheme = "tg", host = "resolve"),
            )
        links.forEach { stubViewIntent(it) }

        for (link in links) {
            assertTrue(link.toString(), client.shouldOverrideUrlLoading(webView, request(link)))
        }

        assertEquals(links.size, started.size)
        links.forEachIndexed { index, link -> assertViewIntent(link, started[index]) }
    }

    @Test
    fun `keeps game store in WebView`() {
        val started = mutableListOf<Intent>()
        val storeUri = stubUri("https://$GAME_HOST/store/", scheme = "https", host = GAME_HOST, path = "/store/")

        assertFalse(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), request(storeUri)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `keeps nested store page of beta server in WebView`() {
        GameUrls.betaServerEnabled = true
        stubUri("https://beta.$GAME_HOST/app", scheme = "https", host = "beta.$GAME_HOST", path = "/app")
        val started = mutableListOf<Intent>()
        val storeUri =
            stubUri(
                "https://beta.$GAME_HOST/store/item/5",
                scheme = "https",
                host = "beta.$GAME_HOST",
                path = "/store/item/5",
            )
        assertFalse(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), request(storeUri)))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `handles Robokassa outside WebView`() {
        // Реальный CustomTabsIntent и сохранение магазина проверяет ExternalLinksE2ETest.
        val payment =
            stubUri(
                "https://auth.robokassa.ru/Merchant/Index.aspx?MerchantLogin=sbg_game&InvoiceID=1",
                scheme = "https",
                host = "auth.robokassa.ru",
                path = "/Merchant/Index.aspx",
            )
        val root = stubUri("https://robokassa.ru/", scheme = "https", host = "robokassa.ru", path = "/")
        assertTrue(client.shouldOverrideUrlLoading(null, request(payment)))
        assertTrue(client.shouldOverrideUrlLoading(null, request(root)))
    }

    @Test
    fun `opens Telegram links externally`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWith(activityRecording(started))
        val links =
            listOf(
                stubUri("https://t.me/sbg_game", scheme = "https", host = "t.me", path = "/sbg_game"),
                stubUri("https://telegram.me/sbg_forum", scheme = "https", host = "telegram.me", path = "/sbg_forum"),
                stubUri("https://www.t.me/sbg_game", scheme = "https", host = "www.t.me", path = "/sbg_game"),
                stubUri("HTTPS://T.ME/sbg_game", scheme = "HTTPS", host = "T.ME", path = "/sbg_game"),
            )
        links.forEach { stubViewIntent(it) }

        for (link in links) {
            assertTrue(link.toString(), client.shouldOverrideUrlLoading(webView, request(link)))
        }

        assertEquals(links.size, started.size)
        links.forEachIndexed { index, link -> assertViewIntent(link, started[index]) }
    }

    @Test
    fun `adds new task flag for non-Activity context`() {
        val started = mutableListOf<Intent>()
        val context = mockk<Context>(relaxed = true)
        every { context.startActivity(capture(started)) } just Runs
        val bankUri = stubBankUri()
        stubViewIntent(bankUri)

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(context), request(bankUri)))

        assertViewIntent(bankUri, started.single())
        verify { viewIntent(bankUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    @Test
    fun `returns true for external link without view`() {
        // Без WebView нет контекста: ссылка не открывается, но и в WebView не грузится.
        assertTrue(client.shouldOverrideUrlLoading(null, request(stubBankUri())))
        verify(exactly = 0) { Toast.makeText(any<Context>(), any<Int>(), any()) }
    }

    // --- shouldOverrideUrlLoading: intent: ---

    @Test
    fun `sanitizes intent link before starting it`() {
        val started = mutableListOf<Intent>()
        val intentUrl = "intent://pay/#Intent;scheme=bank100000000004;package=ru.bank.app;end"
        val parsedIntent = stubParsedIntent(intentUrl)

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), intentRequest(intentUrl)))

        assertSame(parsedIntent, started.single())
        // Как в браузерах: только BROWSABLE-активити, без явного компонента и
        // selector'а, без выдачи доступа к URI (FileProvider приложения).
        val grantFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        verify {
            parsedIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            parsedIntent.component = null
            parsedIntent.selector = null
            parsedIntent.removeFlags(grantFlags)
        }
    }

    @Test
    fun `ignores malformed intent link`() {
        val started = mutableListOf<Intent>()
        val toast = stubToast()
        val intentUrl = "intent://pay/#Intent;unknown;end"
        every { Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME) } throws URISyntaxException(intentUrl, "bad")

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), intentRequest(intentUrl)))

        assertTrue(started.isEmpty())
        verify(exactly = 0) { toast.show() }
    }

    @Test
    fun `ignores intent link with invalid number extra`() {
        // Intent.parseUri не ловит NumberFormatException у extra вида i.key=abc.
        val started = mutableListOf<Intent>()
        val intentUrl = "intent://pay/#Intent;i.amount=abc;end"
        every { Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME) } throws NumberFormatException("abc")

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activityRecording(started)), intentRequest(intentUrl)))

        assertTrue(started.isEmpty())
    }

    @Test
    fun `opens browser fallback url when intent app is missing`() {
        val started = mutableListOf<Intent>()
        val activity = mockk<Activity>(relaxed = true)
        val intentUrl =
            "intent://pay/#Intent;scheme=bank100000000004;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fpay;end"
        val parsedIntent = stubParsedIntent(intentUrl)
        every { parsedIntent.getStringExtra("browser_fallback_url") } returns "https://example.com/pay"
        every { activity.startActivity(any()) } answers {
            val intent = firstArg<Intent>()
            if (intent === parsedIntent) throw ActivityNotFoundException()
            started += intent
        }
        val fallbackUri = stubUri("https://example.com/pay", scheme = "https", host = "example.com", path = "/pay")
        stubViewIntent(fallbackUri)

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activity), intentRequest(intentUrl)))

        assertViewIntent(fallbackUri, started.single())
    }

    @Test
    fun `does not open non-web browser fallback url`() {
        // Fallback со схемой javascript: и прочими не-http(s) не открывается.
        val activity = mockk<Activity>(relaxed = true)
        val toast = stubToast()
        val intentUrl =
            "intent://pay/#Intent;scheme=bank100000000004;" +
                "S.browser_fallback_url=javascript%3Aalert(1);end"
        val parsedIntent = stubParsedIntent(intentUrl)
        every { parsedIntent.getStringExtra("browser_fallback_url") } returns "javascript:alert(1)"
        every { activity.startActivity(any()) } throws ActivityNotFoundException()
        stubUri("javascript:alert(1)", scheme = "javascript")

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activity), intentRequest(intentUrl)))

        verify(exactly = 1) { activity.startActivity(any()) }
        verifyNotFoundToast(activity, toast)
    }

    @Test
    fun `shows toast when intent app is missing and there is no fallback`() {
        val activity = mockk<Activity>(relaxed = true)
        val toast = stubToast()
        val intentUrl = "intent://pay/#Intent;scheme=bank100000000004;end"
        val parsedIntent = stubParsedIntent(intentUrl)
        every { parsedIntent.getStringExtra("browser_fallback_url") } returns null
        every { activity.startActivity(any()) } throws ActivityNotFoundException()

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activity), intentRequest(intentUrl)))

        verifyNotFoundToast(activity, toast)
    }

    // --- shouldOverrideUrlLoading: приложение не запустилось ---

    @Test
    fun `shows toast when no app can open link`() {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.startActivity(any()) } throws ActivityNotFoundException()
        val toast = stubToast()

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activity), request(stubBankUri())))

        verifyNotFoundToast(activity, toast)
    }

    @Test
    fun `shows toast when system refuses to start app`() {
        // SecurityException — у найденной активити есть разрешение, которого нет у Scout.
        val activity = mockk<Activity>(relaxed = true)
        every { activity.startActivity(any()) } throws SecurityException("Permission Denial")
        val toast = stubToast()

        assertTrue(client.shouldOverrideUrlLoading(webViewWith(activity), request(stubBankUri())))

        verifyNotFoundToast(activity, toast)
    }

    // --- onReceivedError ---

    private fun webResourceError(errorCode: Int): WebResourceError {
        val error = mockk<WebResourceError>()
        every { error.errorCode } returns errorCode
        return error
    }

    private fun webViewWithHistory(
        context: Context,
        canGoBack: Boolean,
    ): WebView {
        val webView = webViewWith(context)
        every { webView.canGoBack() } returns canGoBack
        return webView
    }

    @Test
    fun `onReceivedError opens unsupported scheme of main frame externally and goes back`() {
        // Запасной путь: переход на bankXXX:// прошёл мимо shouldOverrideUrlLoading.
        val started = mutableListOf<Intent>()
        val webView = webViewWithHistory(activityRecording(started), canGoBack = true)
        val bankUri = stubBankUri()
        stubViewIntent(bankUri)

        client.onReceivedError(webView, request(bankUri), webResourceError(WebViewClient.ERROR_UNSUPPORTED_SCHEME))

        assertViewIntent(bankUri, started.single())
        verify { webView.goBack() }
    }

    @Test
    fun `onReceivedError opens unsupported scheme without history and stays`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWithHistory(activityRecording(started), canGoBack = false)
        val bankUri = stubBankUri()
        stubViewIntent(bankUri)

        client.onReceivedError(webView, request(bankUri), webResourceError(WebViewClient.ERROR_UNSUPPORTED_SCHEME))

        assertViewIntent(bankUri, started.single())
        verify(exactly = 0) { webView.goBack() }
    }

    @Test
    fun `onReceivedError ignores unsupported scheme of subframe`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWithHistory(activityRecording(started), canGoBack = true)

        client.onReceivedError(
            webView,
            request(stubBankUri(), isForMainFrame = false),
            webResourceError(WebViewClient.ERROR_UNSUPPORTED_SCHEME),
        )

        assertTrue(started.isEmpty())
        verify(exactly = 0) { webView.goBack() }
    }

    @Test
    fun `onReceivedError ignores other errors of main frame`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWithHistory(activityRecording(started), canGoBack = true)
        val uri = stubUri("https://$GAME_HOST/app", scheme = "https", host = GAME_HOST, path = "/app")

        client.onReceivedError(webView, request(uri), webResourceError(WebViewClient.ERROR_HOST_LOOKUP))

        assertTrue(started.isEmpty())
        verify(exactly = 0) { webView.goBack() }
    }

    @Test
    fun `onReceivedError ignores missing view request or error`() {
        val started = mutableListOf<Intent>()
        val webView = webViewWithHistory(activityRecording(started), canGoBack = true)
        val bankRequest = request(stubBankUri())
        val unsupportedScheme = webResourceError(WebViewClient.ERROR_UNSUPPORTED_SCHEME)

        client.onReceivedError(null, bankRequest, unsupportedScheme)
        client.onReceivedError(webView, null, unsupportedScheme)
        client.onReceivedError(webView, bankRequest, null)

        assertTrue(started.isEmpty())
        verify(exactly = 0) { webView.goBack() }
    }

    private companion object {
        const val GAME_HOST = "sbg-game.ru"
    }
}
