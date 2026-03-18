package com.github.wrager.sbguserscripts.script.injector

import android.util.Log
import android.webkit.WebView
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import com.github.wrager.sbguserscripts.script.storage.ScriptStorage
import org.json.JSONArray
import org.json.JSONException

class ScriptInjector(
    private val scriptStorage: ScriptStorage,
    private val applicationId: String,
    private val versionName: String,
) {

    fun inject(webView: WebView, callback: (List<InjectionResult>) -> Unit = {}) {
        injectGlobalVariables(webView)
        injectClipboardPolyfill(webView)
        val enabledScripts = scriptStorage.getEnabled()
        if (enabledScripts.isEmpty()) {
            callback(emptyList())
            return
        }
        for (script in enabledScripts) {
            injectScript(webView, script)
        }
        collectErrors(webView, enabledScripts, callback)
    }

    private fun injectGlobalVariables(webView: WebView) {
        val script = buildGlobalVariablesScript(applicationId, versionName)
        webView.evaluateJavascript(script) {}
    }

    private fun injectClipboardPolyfill(webView: WebView) {
        webView.evaluateJavascript(CLIPBOARD_POLYFILL) {}
    }

    private fun injectScript(webView: WebView, script: UserScript) {
        val wrapped = wrapInSafeIife(
            script.header.name,
            script.content,
            script.header.runAt,
        )
        webView.evaluateJavascript(wrapped) {}
    }

    private fun collectErrors(
        webView: WebView,
        injectedScripts: List<UserScript>,
        callback: (List<InjectionResult>) -> Unit,
    ) {
        webView.evaluateJavascript(READ_ERRORS_SCRIPT) { rawResult ->
            val results = buildResults(injectedScripts, rawResult)
            callback(results)
        }
    }

    companion object {

        private const val TAG = "ScriptInjector"

        private const val READ_ERRORS_SCRIPT =
            "JSON.stringify(window.__sbg_injection_errors || [])"

        internal fun wrapInSafeIife(
            scriptName: String,
            content: String,
            runAt: String? = null,
        ): String {
            val escapedName = scriptName.replace("\\", "\\\\").replace("'", "\\'")
            val body = """
                try {
                    $content
                } catch (error) {
                    console.error('[SBG Userscripts] "$escapedName" failed:', error);
                    window.__sbg_injection_errors = window.__sbg_injection_errors || [];
                    window.__sbg_injection_errors.push({script: '$escapedName', error: String(error)});
                }
            """.trimIndent()

            return if (runAt == "document-start") {
                "(function() {\n$body\n})();"
            } else {
                // document-end, document-idle, или не указано — ждём DOM
                """
                    (function() {
                        function run() {
                            $body
                        }
                        if (document.readyState === 'loading') {
                            document.addEventListener('DOMContentLoaded', run);
                        } else {
                            run();
                        }
                    })();
                """.trimIndent()
            }
        }

        internal fun buildGlobalVariablesScript(
            applicationId: String,
            versionName: String,
        ): String {
            val escapedApplicationId = applicationId.replace("'", "\\'")
            val escapedVersionName = versionName.replace("'", "\\'")
            return """
                window.__sbg_local = false;
                window.__sbg_preset = '';
                window.__sbg_package = '$escapedApplicationId';
                window.__sbg_package_version = '$escapedVersionName';
            """.trimIndent()
        }

        internal fun buildResults(
            injectedScripts: List<UserScript>,
            rawErrorsJson: String?,
        ): List<InjectionResult> {
            val errorsByName = parseErrorsArray(rawErrorsJson)
            return injectedScripts.map { script ->
                val errorMessage = errorsByName[script.header.name]
                if (errorMessage != null) {
                    InjectionResult.ScriptError(script.identifier, errorMessage)
                } else {
                    InjectionResult.Success(script.identifier)
                }
            }
        }

        private fun parseErrorsArray(rawJson: String?): Map<String, String> {
            if (rawJson.isNullOrBlank() || rawJson == "null") return emptyMap()
            // evaluateJavascript возвращает строку в кавычках — нужно unescape
            val json = rawJson.trim().removeSurrounding("\"").replace("\\\"", "\"")
            return try {
                val array = JSONArray(json)
                val result = mutableMapOf<String, String>()
                for (index in 0 until array.length()) {
                    val entry = array.getJSONObject(index)
                    val scriptName = entry.optString("script", "")
                    val error = entry.optString("error", "Unknown error")
                    if (scriptName.isNotEmpty()) {
                        result[scriptName] = error
                    }
                }
                result
            } catch (exception: JSONException) {
                Log.e(TAG, "Failed to parse injection errors", exception)
                emptyMap()
            }
        }

        private val CLIPBOARD_POLYFILL = """
            (function() {
                if (navigator.clipboard) return;
                navigator.clipboard = {
                    readText: function() {
                        return new Promise(function(resolve) {
                            resolve(Android.readText());
                        });
                    },
                    writeText: function(text) {
                        return new Promise(function(resolve) {
                            Android.writeText(text);
                            resolve();
                        });
                    }
                };
            })();
        """.trimIndent()
    }
}
