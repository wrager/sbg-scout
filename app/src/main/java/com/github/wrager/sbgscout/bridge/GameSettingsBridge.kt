package com.github.wrager.sbgscout.bridge

import android.webkit.JavascriptInterface

/**
 * JS-бридж для получения уведомлений об изменении настроек игры.
 *
 * В WebView инжектируется обёртка над `localStorage.setItem`, которая
 * при записи ключа `'settings'` вызывает [onSettingsChanged] через этот бридж.
 */
class GameSettingsBridge(private val listener: (String) -> Unit) {

    @JavascriptInterface
    fun onSettingsChanged(json: String) {
        listener(json)
    }

    companion object {
        const val JS_INTERFACE_NAME = "__sbg_settings"

        /**
         * JS-код для перехвата `localStorage.setItem('settings', ...)`.
         *
         * Инжектируется в `onPageStarted` до скриптов игры, чтобы
         * отловить все записи в ключ 'settings' (включая `initSettings()`
         * и пользовательские изменения через `changeSettings()`).
         */
        val LOCAL_STORAGE_WRAPPER = """
            (function() {
                var orig = localStorage.setItem;
                localStorage.setItem = function(key, value) {
                    orig.call(localStorage, key, value);
                    if (key === 'settings' && window.$JS_INTERFACE_NAME) {
                        try { $JS_INTERFACE_NAME.onSettingsChanged(value); } catch(e) {}
                    }
                };
            })();
        """.trimIndent()
    }
}
