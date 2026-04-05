package com.github.wrager.sbgscout.webview

import android.app.Activity
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.bridge.GameSettingsBridge
import com.github.wrager.sbgscout.bridge.ScoutBridge
import com.github.wrager.sbgscout.script.injector.InjectionResult
import com.github.wrager.sbgscout.script.injector.ScriptInjector

class SbgWebViewClient(
    private val scriptInjector: ScriptInjector,
) : WebViewClient() {

    /** Вызывается после загрузки страницы игры с текущим значением настроек. */
    var onGameSettingsRead: ((String?) -> Unit)? = null

    /** Вызывается при старте загрузки страницы игры (в т.ч. при reload). */
    var onGamePageStarted: (() -> Unit)? = null

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url?.contains("sbg-game.ru/app") == true && view != null) {
            // Перехват localStorage.setItem ПЕРЕД инжекцией скриптов,
            // чтобы отловить любые записи в 'settings'
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
        if (url?.contains("sbg-game.ru/app") == true && view != null) {
            // Начальное чтение настроек (на случай если localStorage уже заполнен
            // до инжекции обёртки, например при навигации по истории)
            onGameSettingsRead?.let { callback ->
                view.evaluateJavascript("localStorage.getItem('settings')") { result ->
                    callback(unescapeJsString(result))
                }
            }
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

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
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
