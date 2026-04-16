package com.github.wrager.sbgscout.script.injector

import android.util.Log
import android.webkit.WebView
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.json.JSONArray
import org.json.JSONException

class ScriptInjector(
    private val scriptStorage: ScriptStorage,
    private val applicationId: String,
    private val versionName: String,
    private val injectionStateStorage: InjectionStateStorage? = null,
) {

    fun inject(webView: WebView, callback: (List<InjectionResult>) -> Unit = {}) {
        injectDocumentWriteEventFix(webView)
        injectGlobalVariables(webView)
        injectClipboardPolyfill(webView)
        val enabledScripts = sortByInjectionPriority(scriptStorage.getEnabled())
        injectionStateStorage?.saveSnapshot(enabledScripts)
        if (enabledScripts.isEmpty()) {
            callback(emptyList())
            return
        }
        for (script in enabledScripts) {
            injectScript(webView, script)
        }
        collectErrors(webView, enabledScripts, callback)
    }

    private fun injectDocumentWriteEventFix(webView: WebView) {
        webView.evaluateJavascript(DOCUMENT_WRITE_EVENT_FIX) {}
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

        // Скрипт, содержащий document.open(), перестраивает страницу целиком
        // и должен инжектироваться раньше остальных — иначе другие скрипты
        // работают с DOM, который будет уничтожен.
        private val DOCUMENT_OPEN_PATTERN = Regex("""document\s*\.\s*open\s*\(""")

        internal fun rewritesDocument(content: String): Boolean =
            DOCUMENT_OPEN_PATTERN.containsMatchIn(content)

        internal fun sortByInjectionPriority(scripts: List<UserScript>): List<UserScript> =
            scripts.sortedByDescending { rewritesDocument(it.content) }

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
                    console.error('[SBG Scout] "$escapedName" failed:', error);
                    window.__sbg_injection_errors = window.__sbg_injection_errors || [];
                    window.__sbg_injection_errors.push({script: '$escapedName', error: String(error)});
                }
            """.trimIndent()

            return if (runAt == "document-start") {
                "(function() {\n$body\n})();"
            } else {
                // document-end, document-idle или не указано — ждём DOMContentLoaded.
                // Tampermonkey различает document-end (DOMContentLoaded) и document-idle
                // (DOMContentLoaded + async yield), но Scout не может воспроизвести
                // эту разницу: у WebView host нет доступа к Chrome'овской эвристике
                // document_idle, а любая фиксированная задержка (даже setTimeout(0))
                // ломает скрипты вроде CUI, которые зависят от window.stop() до
                // выполнения game-скрипта. Порядок инжекции при наличии нескольких
                // скриптов контролируется через sortByInjectionPriority.
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
                    InjectionResult.ScriptError(script.identifier, script.header.name, errorMessage)
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

        // Workaround: document.write() в некоторых версиях WebView (в т.ч. 101)
        // сбрасывает window event listeners. CUI регистрирует olReady/mapReady
        // listeners после document.open(), затем вызывает document.write() для
        // перестройки страницы. olReady диспатчится ВНУТРИ document.write()
        // (из onload OL-скрипта), но listener уже потерян.
        // Фикс: сохраняем *Ready listeners при регистрации. После document.close()
        // перерегистрируем потерянные listeners и re-dispatch события.
        private val DOCUMENT_WRITE_EVENT_FIX = """
            (function() {
                var insideDocWrite = false;
                var lostEvents = [];
                var listenersCalledDuringWrite = {};
                var savedListeners = [];

                var origAddEventListener = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, fn, opts) {
                    if (this === window && /Ready/i.test(type)) {
                        savedListeners.push({ type: type, fn: fn, opts: opts });
                        var wrappedFn = function(event) {
                            if (insideDocWrite) {
                                listenersCalledDuringWrite[type] = true;
                            }
                            return fn.call(this, event);
                        };
                        return origAddEventListener.call(this, type, wrappedFn, opts);
                    }
                    return origAddEventListener.call(this, type, fn, opts);
                };

                var origDispatch = window.dispatchEvent.bind(window);
                window.dispatchEvent = function(event) {
                    var result = origDispatch(event);
                    var eventType = event && event.type ? event.type : '';
                    if (insideDocWrite && /Ready/i.test(eventType)) {
                        if (!listenersCalledDuringWrite[eventType]) {
                            lostEvents.push(eventType);
                        }
                    }
                    return result;
                };

                var origDocWrite = Document.prototype.write;
                Document.prototype.write = function() {
                    insideDocWrite = true;
                    listenersCalledDuringWrite = {};
                    try {
                        return origDocWrite.apply(this, arguments);
                    } finally {
                        insideDocWrite = false;
                    }
                };

                var origDocClose = Document.prototype.close;
                Document.prototype.close = function() {
                    var result = origDocClose.apply(this, arguments);
                    if (lostEvents.length > 0) {
                        var events = lostEvents.slice();
                        lostEvents = [];
                        // Перерегистрируем потерянные listeners
                        savedListeners.forEach(function(entry) {
                            origAddEventListener.call(window, entry.type, entry.fn, entry.opts);
                        });
                        // Re-dispatch потерянных событий
                        events.forEach(function(eventType) {
                            console.log('[SBG Fix] Re-dispatching lost event:', eventType);
                            window.dispatchEvent(new Event(eventType));
                        });
                    }
                    return result;
                };
            })();
        """.trimIndent()

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
