package com.github.wrager.sbgscout.webview

import android.app.Activity
import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
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
            // UA override ПЕРВЫМ, до любых bootstrap/пользовательских скриптов:
            // EUI читает navigator.userAgent в IsWebView() и отключает импорт/экспорт
            // при наличии подстроки "wv". Подменяем геттер на JS-уровне, чтобы
            // EUI/SVP видели очищенную строку; реальный UA не трогаем.
            view.evaluateJavascript(UserAgentOverride.BOOTSTRAP_SCRIPT) {}
            // Перехватчики blob-скачиваний и localStorage ПЕРЕД инжекцией скриптов.
            // Download-перехват должен быть установлен до того, как юзерскрипт
            // успеет вызвать URL.createObjectURL — иначе blob не попадёт в кеш.
            view.evaluateJavascript(DownloadBridge.BOOTSTRAP_SCRIPT) {}
            view.evaluateJavascript(GameSettingsBridge.LOCAL_STORAGE_WRAPPER) {}
            // Bootstrap для большой кнопки настроек: наблюдает за готовностью игры,
            // скрывает нативную кнопку и вставляет HTML-кнопку в .settings-content
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
            // Начальное чтение настроек (на случай если localStorage уже заполнен
            // до инжекции обёртки, например при навигации по истории)
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
        // Все URL (включая Telegram OAuth) загружаются в WebView
        return false
    }

    companion object {
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
