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
        val enabledScripts = sortByInjectionPriority(scriptStorage.getEnabled())
        injectionStateStorage?.saveSnapshot(enabledScripts)

        val payload = buildInjectionPayload(enabledScripts)
        webView.evaluateJavascript(payload) {}

        if (enabledScripts.isEmpty()) {
            callback(emptyList())
        } else {
            collectErrors(webView, enabledScripts, callback)
        }
    }

    /**
     * Собирает ВСЮ инжекцию в одну строку для единственного
     * `evaluateJavascript`-вызова. Это критично для корректности:
     * каждый `evaluateJavascript` — отдельная задача в очереди WebView
     * renderer thread, и DOMContentLoaded может вклиниться между задачами.
     * Один вызов = одна атомарная задача = детерминированный тайминг.
     */
    /**
     * Собирает ВСЮ инжекцию в одну строку для единственного
     * `evaluateJavascript`-вызова: preamble (event fix, globals, polyfill)
     * + скрипты, сгруппированные по @run-at.
     *
     * Один вызов = одна задача в очереди WebView renderer = атомарная
     * обработка. DOMContentLoaded не может вклиниться между скриптами.
     */
    internal fun buildInjectionPayload(enabledScripts: List<UserScript>): String {
        val parts = mutableListOf<String>()

        // Preamble: event fix, globals, polyfill — выполняются немедленно
        parts.add(DOCUMENT_WRITE_EVENT_FIX)
        parts.add(buildGlobalVariablesScript(applicationId, versionName))
        parts.add(CLIPBOARD_POLYFILL)

        if (enabledScripts.isNotEmpty()) {
            val (startScripts, deferredScripts) = enabledScripts.partition {
                it.header.runAt == "document-start"
            }
            if (startScripts.isNotEmpty()) {
                parts.add(buildImmediateBatch(startScripts))
            }
            if (deferredScripts.isNotEmpty()) {
                parts.add(buildDeferredBatch(deferredScripts))
            }
        }

        return parts.joinToString("\n")
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

        /**
         * Оборачивает скрипт в IIFE — аналог Tampermonkey'шной обёртки
         * `(function() { 'use strict'; ... })()`. Скрипты изолированы
         * друг от друга, ошибка одного не блокирует остальные.
         */
        internal fun wrapScript(scriptName: String, content: String): String {
            val escapedName = scriptName.replace("\\", "\\\\").replace("'", "\\'")
            return """
                (function() {
                    try {
                        $content
                    } catch (error) {
                        console.error('[SBG Scout] "$escapedName" failed:', error);
                        window.__sbg_injection_errors = window.__sbg_injection_errors || [];
                        window.__sbg_injection_errors.push({script: '$escapedName', error: String(error)});
                    }
                })();
            """.trimIndent()
        }

        /**
         * Собирает document-start скрипты. Выполняются немедленно.
         */
        internal fun buildImmediateBatch(scripts: List<UserScript>): String =
            scripts.joinToString("\n") { wrapScript(it.header.name, it.content) }

        /** Оборачивает JS-код в DOMContentLoaded handler (аналог Tampermonkey document-idle). */
        private fun wrapInDomContentLoaded(code: String): String = """
            (function() {
                function run() {
                    $code
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', run);
                } else {
                    run();
                }
            })();
        """.trimIndent()

        /**
         * Собирает deferred-скрипты (document-end, document-idle, без @run-at).
         *
         * Rewriters (document.open) → DOMContentLoaded (как Tampermonkey).
         * Regular → DOMContentLoaded (если нет rewriters) или readyState
         * polling (если есть rewriters: WebView не файрит DOMContentLoaded
         * после document.close — подтверждено диагностикой).
         */
        internal fun buildDeferredBatch(scripts: List<UserScript>): String {
            val rewriters = scripts.filter { rewritesDocument(it.content) }
            val regular = scripts.filter { !rewritesDocument(it.content) }

            val parts = mutableListOf<String>()

            if (rewriters.isNotEmpty()) {
                val wrappedRewriters = rewriters.joinToString("\n") {
                    wrapScript(it.header.name, it.content)
                }
                // cuiStatus='initializing': guard для EUI polling (без него
                // polling ловит readyState='interactive' до запуска CUI).
                parts.add("window.cuiStatus = 'initializing';")
                parts.add(wrapInDomContentLoaded(wrappedRewriters))
            }

            if (regular.isNotEmpty()) {
                val wrappedRegular = regular.joinToString("\n") {
                    wrapScript(it.header.name, it.content)
                }
                if (rewriters.isNotEmpty()) {
                    // WebView уничтожает DOMContentLoaded listener'ы при
                    // document.open() (подтверждено диагностикой). Polling
                    // readyState + cuiStatus guard — workaround.
                    parts.add(
                        """
                        (function() {
                            (function waitForDom() {
                                if (document.readyState !== 'loading' && window.cuiStatus !== 'initializing') {
                                    $wrappedRegular
                                } else {
                                    setTimeout(waitForDom, 10);
                                }
                            })();
                        })();
                        """.trimIndent(),
                    )
                } else {
                    parts.add(wrapInDomContentLoaded(wrappedRegular))
                }
            }

            return parts.joinToString("\n")
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
        //
        // Фикс: сохраняем *Ready listeners при регистрации. После
        // document.close() перерегистрируем потерянные listeners и
        // re-dispatch события. DOMContentLoaded НЕ обрабатывается здесь —
        // скрипты без document.open() запускаются через polling readyState
        // в buildDeferredBatch, что не зависит от event listeners.
        internal const val EVENT_PRESERVE_PATTERN = "Ready"

        private val DOCUMENT_WRITE_EVENT_FIX = """
            (function() {
                var insideDocWrite = false;
                var lostEvents = [];
                var listenersCalledDuringWrite = {};
                var savedListeners = [];
                var preservePattern = /$EVENT_PRESERVE_PATTERN/i;

                // При JS reload (location.reload) window сохраняется, и
                // предыдущий event fix оставляет patched prototypes. Без
                // восстановления оригиналов новый патч ложится поверх старого
                // (двойная обёртка), старые *Ready listeners выживают, и CUI's
                // main() вызывается дважды. Восстанавливаем оригиналы перед
                // повторным патчингом.
                if (window.__sbg_event_fix_originals) {
                    var orig = window.__sbg_event_fix_originals;
                    EventTarget.prototype.addEventListener = orig.addEventListener;
                    Document.prototype.write = orig.write;
                    Document.prototype.close = orig.close;
                    window.dispatchEvent = orig.dispatchEvent;
                }

                var origAddEventListener = EventTarget.prototype.addEventListener;
                window.__sbg_event_fix_originals = {
                    addEventListener: origAddEventListener,
                    write: Document.prototype.write,
                    close: Document.prototype.close,
                    dispatchEvent: window.dispatchEvent.bind(window),
                };
                EventTarget.prototype.addEventListener = function(type, fn, opts) {
                    if (this === window && preservePattern.test(type)) {
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
                    if (insideDocWrite && preservePattern.test(eventType)) {
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
                        savedListeners.forEach(function(entry) {
                            origAddEventListener.call(window, entry.type, entry.fn, entry.opts);
                        });
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
