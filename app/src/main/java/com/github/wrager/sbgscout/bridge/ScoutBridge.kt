package com.github.wrager.sbgscout.bridge

import android.webkit.JavascriptInterface

/**
 * JS-бридж для взаимодействия кнопки «Scout» с игрой.
 *
 * Bootstrap-скрипт, инжектируемый в onPageStarted, ждёт инициализацию i18next и:
 * 1. Вызывает [onGameReady] — игра готова, Android снимает подложку загрузки.
 * 2. Пытается вставить строчку «SBG Scout settings» в игровую панель настроек
 *    (`.settings-content`). При успехе вызывает [onHtmlButtonInjected] — Android
 *    скрывает нативную кнопку. Под скриптами, переделывающими UI (CUI),
 *    `.settings-content` может отсутствовать — инжекция пропускается, нативная
 *    кнопка остаётся.
 * 3. Клик по HTML-кнопке вызывает [openScoutSettings] — открыть экран настроек.
 */
class ScoutBridge(
    private val onReady: () -> Unit,
    private val onHtmlInjected: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {

    @JavascriptInterface
    fun onGameReady() {
        onReady()
    }

    @JavascriptInterface
    fun onHtmlButtonInjected() {
        onHtmlInjected()
    }

    @JavascriptInterface
    fun openScoutSettings() {
        onOpenSettings()
    }

    companion object {
        const val JS_INTERFACE_NAME = "__sbg_scout"

        /**
         * Bootstrap-скрипт — polling за `window.i18next.isInitialized`.
         *
         * Сигнал готовности — инициализация i18next: универсален и не зависит от
         * DOM-разметки, поэтому работает и под скриптами, переделывающими UI
         * (CUI, EUI). Предыдущая версия смотрела на `#settings` textContent — не
         * срабатывала под CUI, который меняет игровой интерфейс.
         *
         * Запуск откладывается до DOMContentLoaded: evaluateJavascript в
         * onPageStarted выполняется до того как реальный document создан.
         *
         * Локализация HTML-кнопки: читается `localStorage['settings'].lang`;
         * если язык начинается с "ru" — русские строки, иначе английские.
         *
         * Нет таймаута: polling продолжается пока i18next не инициализируется.
         * Если этого не случится (нет сети) — подложка загрузки не снимается,
         * нативная «Scout» остаётся единственным способом открыть настройки.
         */
        val BOOTSTRAP_SCRIPT = """
            (function() {
                var STRINGS = {
                    ru: { item: 'Настройки SBG Scout', button: 'Открыть' },
                    en: { item: 'SBG Scout settings', button: 'Open' }
                };

                function pickStrings() {
                    try {
                        var raw = localStorage.getItem('settings');
                        if (raw) {
                            var parsed = JSON.parse(raw);
                            var lang = (parsed && parsed.lang) || '';
                            if (lang.indexOf('ru') === 0) return STRINGS.ru;
                        }
                    } catch (e) {}
                    return STRINGS.en;
                }

                function injectButton() {
                    if (document.getElementById('sbg-scout-settings-btn')) return true;
                    var content = document.querySelector('.settings-content');
                    if (!content) return false;
                    var strings = pickStrings();
                    var item = document.createElement('div');
                    item.className = 'settings-section__item';
                    var label = document.createElement('span');
                    label.textContent = strings.item;
                    var button = document.createElement('button');
                    button.className = 'settings-section__button';
                    button.id = 'sbg-scout-settings-btn';
                    button.textContent = strings.button;
                    button.addEventListener('click', function() {
                        if (window.__sbg_scout) window.__sbg_scout.openScoutSettings();
                    });
                    item.appendChild(label);
                    item.appendChild(button);
                    content.insertBefore(item, content.firstChild);
                    return true;
                }

                function checkReady() {
                    return !!(window.i18next && window.i18next.isInitialized);
                }

                function onReady() {
                    if (window.__sbg_scout) window.__sbg_scout.onGameReady();
                    if (injectButton() && window.__sbg_scout) {
                        window.__sbg_scout.onHtmlButtonInjected();
                    }
                }

                function start() {
                    if (window.__sbg_scout_bootstrapped) return;
                    window.__sbg_scout_bootstrapped = true;
                    if (checkReady()) {
                        onReady();
                        return;
                    }
                    var interval = setInterval(function() {
                        if (checkReady()) {
                            clearInterval(interval);
                            onReady();
                        }
                    }, 100);
                }

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', start);
                } else {
                    start();
                }
            })();
        """.trimIndent()
    }
}
