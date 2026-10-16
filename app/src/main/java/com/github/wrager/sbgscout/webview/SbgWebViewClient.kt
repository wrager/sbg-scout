package com.github.wrager.sbgscout.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.bridge.DownloadBridge
import com.github.wrager.sbgscout.bridge.GameSettingsBridge
import com.github.wrager.sbgscout.bridge.ScoutBridge
import com.github.wrager.sbgscout.config.GameUrls
import com.github.wrager.sbgscout.script.injector.InjectionResult
import com.github.wrager.sbgscout.script.injector.ScriptInjector
import java.net.URISyntaxException

class SbgWebViewClient(
    private val scriptInjector: ScriptInjector,
) : WebViewClient() {

    /** Вызывается после загрузки страницы игры с текущим значением настроек. */
    var onGameSettingsRead: ((String?) -> Unit)? = null

    /** Вызывается при старте загрузки страницы игры (в т.ч. при reload). */
    var onGamePageStarted: (() -> Unit)? = null

    @Volatile
    private var gamePageFinishedAtLeastOnce = false


    /**
     * Вызывается после `onPageFinished` страницы игры.
     * Используется в androidTest как сигнал для IdlingResource:
     * на этом моменте JS-мосты уже зарегистрированы и WebView готова
     * к вызовам `evaluateJavascript`.
     *
     * Setter idempotent по истории: если к моменту установки страница уже
     * была загружена хотя бы раз, callback вызывается сразу. Это лечит race
     * condition в e2e: на localhost загрузка может завершиться раньше, чем
     * тест успеет подписаться через `scenario.onActivity`.
     */
    var onGamePageFinished: (() -> Unit)? = null
        set(value) {
            field = value
            if (gamePageFinishedAtLeastOnce) value?.invoke()
        }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (GameUrls.isGameAppPage(url) && view != null) {
            view.evaluateJavascript(UserAgentOverride.BOOTSTRAP_SCRIPT) {}
            view.evaluateJavascript(DownloadBridge.BOOTSTRAP_SCRIPT) {}
            view.evaluateJavascript(GameSettingsBridge.LOCAL_STORAGE_WRAPPER) {}
            view.evaluateJavascript(ScoutBridge.BOOTSTRAP_SCRIPT) {}
            onGamePageStarted?.invoke()
            scriptInjector.inject(view) { results ->
                handleInjectionResults(view, results)
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (GameUrls.isGameAppPage(url) && view != null) {
            onGameSettingsRead?.let { callback ->
                view.evaluateJavascript("localStorage.getItem('settings')") { result ->
                    callback(unescapeJsString(result))
                }
            }
            gamePageFinishedAtLeastOnce = true
            onGamePageFinished?.invoke()
        }
    }

    private fun handleInjectionResults(view: WebView, results: List<InjectionResult>) {
        val errors = results.filterIsInstance<InjectionResult.ScriptError>()
        if (errors.isEmpty()) return
        val scriptNames = errors.joinToString(", ") {
            it.scriptName.ifBlank { it.identifier.value }
        }
        val message = view.context.getString(R.string.script_execution_error, scriptNames)
        Toast.makeText(view.context, message, Toast.LENGTH_LONG).show()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        // Запасной путь для переходов мимо shouldOverrideUrlLoading (например,
        // перенаправления на bankXXX:// в ответ на POST): ссылку открывает система,
        // а страница ошибки WebView убирается.
        if (view != null && request?.isForMainFrame == true &&
            error?.errorCode == ERROR_UNSUPPORTED_SCHEME
        ) {
            openExternally(view.context, request.url.toString())
            if (view.canGoBack()) view.goBack()
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        // Renderer-процесс упал или был убит системой.
        // false = дефолтное поведение (краш приложения).
        // При необходимости можно заменить на graceful recovery (пересоздание WebView).
        return false
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val url = uri.toString()
        if (url.contains("window.close")) {
            val context = view?.context
            if (context is Activity) context.finish()
            return true
        }
        if (shouldStayInWebView(uri, request.isForMainFrame)) return false
        view?.context?.let { openExternally(it, url) }
        return true
    }

    /**
     * Остаётся ли переход в WebView.
     *
     * Не-веб схемы (`bankXXX://` для оплаты через СБП, `intent:`, `tel:`, `mailto:`)
     * WebView не открывает (`ERR_UNKNOWN_URL_SCHEME`), поэтому они уходят системе
     * в любом фрейме. Веб-страницы в основном фрейме уходят наружу, только если это
     * магазин игры или [EXTERNAL_WEB_HOSTS].
     *
     * Магазин открывается в браузере, чтобы вся покупка прошла в одном месте:
     * после оплаты приложение банка само открывает адрес возврата в браузере
     * по умолчанию, а Scout остаётся на игре. Для заказа в браузере нужен вход
     * в аккаунт игры.
     */
    private fun shouldStayInWebView(uri: Uri, isForMainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase()
        return when {
            scheme in INTERNAL_SCHEMES -> true
            scheme !in WEB_SCHEMES -> false
            !isForMainFrame -> true
            else -> !isExternalWebHost(uri.host) && !GameUrls.isGameStorePage(uri.toString())
        }
    }

    private fun isExternalWebHost(host: String?): Boolean {
        val normalizedHost = host?.lowercase() ?: return false
        return EXTERNAL_WEB_HOSTS.any { normalizedHost == it || normalizedHost.endsWith(".$it") }
    }

    /**
     * Открывает ссылку вне WebView: в приложении банка, Telegram или браузере.
     * Если запустить приложение не удалось, для `intent:` открывается его
     * `browser_fallback_url`, иначе показывается сообщение.
     */
    private fun openExternally(context: Context, url: String) {
        val intent = createExternalIntent(url) ?: return
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startActivitySafely(context, intent)) return
        val fallbackUrl = intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL)
        if (fallbackUrl != null && isWebUrl(fallbackUrl)) {
            openExternally(context, fallbackUrl)
        } else {
            Toast.makeText(context, R.string.external_app_not_found, Toast.LENGTH_LONG).show()
        }
    }

    private fun createExternalIntent(url: String): Intent? {
        if (url.startsWith(INTENT_SCHEME_PREFIX, ignoreCase = true)) return parseIntentUri(url)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // Ссылки сайта игры Scout принимает сам (deep link). Чтобы магазин
            // открылся в браузере, а не вернулся в Scout, приложение выбирается
            // по ссылке без хоста — под неё подходят только браузеры.
            if (GameUrls.isGameStorePage(url)) {
                selector = Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_SELECTOR_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
    }

    /**
     * `intent:`-ссылку со страницы ограничиваем, как браузеры: только активити
     * с категорией BROWSABLE, без явного компонента и selector'а. Флаги выдачи
     * доступа к URI сбрасываются: у приложения есть FileProvider с
     * `grantUriPermissions`, и ссылка со страницы не должна открывать к нему доступ.
     *
     * Некорректная ссылка игнорируется: `parseUri` бросает `URISyntaxException`,
     * а на нечисловом значении числового extra — `NumberFormatException`.
     */
    private fun parseIntentUri(url: String): Intent? =
        try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                removeFlags(URI_PERMISSION_GRANT_FLAGS)
            }
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * false, если подходящего приложения нет или система не дала его запустить
     * (`SecurityException` у активити, закрытой разрешением).
     */
    private fun startActivitySafely(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun isWebUrl(url: String): Boolean = Uri.parse(url).scheme?.lowercase() in WEB_SCHEMES

    companion object {
        private val WEB_SCHEMES = setOf("http", "https")

        /** Схемы, которые WebView открывает сам: служебные страницы и содержимое самой страницы. */
        private val INTERNAL_SCHEMES = setOf("about", "data", "blob", "javascript", "file")

        /**
         * Хосты (вместе с поддоменами), которые в основном фрейме открываются вне WebView.
         *
         * Робокасса — страховка на случай, если магазин всё же открыт в WebView
         * (например, по ссылке на магазин из другого приложения): оплата тогда
         * уходит в браузер, а WebView остаётся на странице магазина.
         *
         * Ссылки Telegram открываются в приложении Telegram. `oauth.telegram.org`
         * сюда не входит: вход в игру должен пройти в WebView.
         */
        private val EXTERNAL_WEB_HOSTS = setOf("robokassa.ru", "t.me", "telegram.me")

        private const val INTENT_SCHEME_PREFIX = "intent:"
        private const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"

        /** Ссылка без хоста для selector'а: под неё подходят только браузеры. */
        private const val BROWSER_SELECTOR_URL = "https://"

        private const val URI_PERMISSION_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        /**
         * evaluateJavascript возвращает JS-значение как строку в кавычках.
         * Для `localStorage.getItem(...)` результат — JSON-строка в кавычках
         * с экранированными внутренними кавычками, или литерал `"null"`.
         */
        internal fun unescapeJsString(raw: String?): String? {
            if (raw.isNullOrBlank() || raw == "null") return null
            return raw
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .takeIf { it != "null" }
        }
    }
}
