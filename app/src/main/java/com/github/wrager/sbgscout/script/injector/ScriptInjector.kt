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
         * Оборачивает скрипт в IIFE с try-catch для изоляции ошибок.
         * Не добавляет логику тайминга — за это отвечают [buildDeferredBatch]
         * и [buildImmediateBatch].
         */
        internal fun wrapInTryCatch(scriptName: String, content: String): String {
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
         * Собирает document-start скрипты в один evaluateJavascript-вызов.
         * Каждый скрипт в отдельном IIFE с try-catch, выполняются немедленно.
         */
        internal fun buildImmediateBatch(scripts: List<UserScript>): String =
            scripts.joinToString("\n") { wrapInTryCatch(it.header.name, it.content) }

        /**
         * Собирает deferred-скрипты (document-end, document-idle, без @run-at)
         * в один evaluateJavascript-вызов с единым DOMContentLoaded-обработчиком.
         *
         * Все скрипты стартуют в одном handler — DOMContentLoaded не может
         * вклиниться между ними, и порядок выполнения детерминирован.
         * Скрипты, перестраивающие страницу (document.open), должны идти
         * первыми (см. [sortByInjectionPriority]): их document.open()
         * сбрасывает readyState в 'loading', и следующие скрипты, проверяющие
         * readyState внутри себя, корректно дожидаются нового DOMContentLoaded.
         */
        internal fun buildDeferredBatch(scripts: List<UserScript>): String {
            val wrappedScripts = scripts.joinToString("\n") { script ->
                val prefix = if (rewritesDocument(script.content)) {
                    // Скрипт с document.open() перестраивает страницу асинхронно.
                    // Другие скрипты (EUI) проверяют window.cuiStatus для обнаружения
                    // CUI, но CUI выставляет свои глобалы (cuiStatus, TeamColors и др.)
                    // только после полной асинхронной инициализации. На первом запуске
                    // глобалов нет, и CUI.Detected() возвращает false — EUI идёт по
                    // неправильному пути (delay 500ms вместо 30-сек polling).
                    // На перезагрузке глобалы остаются от предыдущей сессии и
                    // CUI.Detected() работает.
                    // Маркер 'initializing' — легитимный статус CUI, который EUI
                    // уже обрабатывает (CUI.Initializing: () => cuiStatus == 'initializing').
                    "window.cuiStatus = 'initializing';\n"
                } else {
                    ""
                }
                prefix + wrapInTryCatch(script.header.name, script.content)
            }
            return """
                (function() {
                    function runAll() {
                        $wrappedScripts
                    }
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', runAll);
                    } else {
                        runAll();
                    }
                })();
            """.trimIndent()
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
        // Дополнительно: EUI после document.open() от CUI регистрирует
        // window.addEventListener('DOMContentLoaded', ...) для ожидания
        // нового документа. Без сохранения DOMContentLoaded-listener
        // EUI никогда не стартует.
        //
        // Паттерн /Ready|DOMContentLoaded/i покрывает:
        // - CUI: dbReady, olReady, mapReady
        // - EUI: DOMContentLoaded
        //
        // Фикс: сохраняем matching listeners при регистрации. После
        // document.close() перерегистрируем потерянные listeners и
        // re-dispatch события.
        internal const val EVENT_PRESERVE_PATTERN = "Ready|DOMContentLoaded"

        private val DOCUMENT_WRITE_EVENT_FIX = """
            (function() {
                var insideDocWrite = false;
                var lostEvents = [];
                var listenersCalledDuringWrite = {};
                var savedListeners = [];
                var preservePattern = /$EVENT_PRESERVE_PATTERN/i;

                var origAddEventListener = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, fn, opts) {
                    if (this === window && preservePattern.test(type)) {
                        var entry = { type: type, fn: fn, opts: opts, invoked: false };
                        savedListeners.push(entry);
                        var wrappedFn = function(event) {
                            if (entry.invoked) return;
                            entry.invoked = true;
                            if (insideDocWrite) {
                                listenersCalledDuringWrite[type] = true;
                            }
                            return fn.call(this, event);
                        };
                        // DOMContentLoaded: once=true чтобы listener автоматически
                        // снялся после первого вызова. На Chrome 146+ listener
                        // выживает document.write() — без once wrappedFn сработал бы
                        // повторно на DOMContentLoaded нового документа.
                        var regOpts = opts;
                        if (type === 'DOMContentLoaded') {
                            if (typeof opts === 'object' && opts !== null) {
                                regOpts = Object.assign({}, opts, { once: true });
                            } else if (typeof opts === 'boolean') {
                                regOpts = { capture: opts, once: true };
                            } else {
                                regOpts = { once: true };
                            }
                        }
                        return origAddEventListener.call(this, type, wrappedFn, regOpts);
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
                    // *Ready listeners (olReady, mapReady и др.) перерегистрируем
                    // только когда есть потерянные события — на современных WebView
                    // (Chrome 146+) document.write() НЕ уничтожает window listeners,
                    // и безусловная перерегистрация дублирует их (main() вызывается
                    // дважды → сломанные touch handlers → нерабочий поворот карты).
                    if (lostEvents.length > 0) {
                        var events = lostEvents.slice();
                        lostEvents = [];
                        savedListeners.forEach(function(entry) {
                            if (entry.type !== 'DOMContentLoaded') {
                                origAddEventListener.call(window, entry.type, entry.fn, entry.opts);
                            }
                        });
                        events.forEach(function(eventType) {
                            console.log('[SBG Fix] Re-dispatching lost event:', eventType);
                            window.dispatchEvent(new Event(eventType));
                        });
                    }
                    // DOMContentLoaded: wrappedFn зарегистрирован с once=true и
                    // ставит entry.invoked=true при вызове. Если DOMContentLoaded
                    // уже сработал (wrappedFn вызвал fn) — entry.invoked=true,
                    // пропускаем. Если listener был уничтожен (WebView 101) или
                    // DOMContentLoaded ещё не сработал — вызываем fn из кеша.
                    var domListeners = savedListeners.filter(function(entry) {
                        return entry.type === 'DOMContentLoaded';
                    });
                    if (domListeners.length > 0) {
                        var domReady = document.readyState !== 'loading';
                        domListeners.forEach(function(entry) {
                            if (entry.invoked) return;
                            if (domReady) {
                                entry.invoked = true;
                                try { entry.fn(new Event('DOMContentLoaded')); } catch(e) { console.error(e); }
                            } else {
                                origAddEventListener.call(window, entry.type, entry.fn, entry.opts);
                            }
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
