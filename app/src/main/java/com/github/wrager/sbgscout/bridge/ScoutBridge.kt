package com.github.wrager.sbgscout.bridge

import android.webkit.JavascriptInterface

/**
 * JS-бридж для взаимодействия большой кнопки «Настройки SBG Scout» с игрой.
 *
 * Bootstrap-скрипт, инжектируемый в onPageStarted, наблюдает за применением
 * переводов i18next и:
 * 1. Вызывает [onGameReady], чтобы Android скрыл большую нативную кнопку.
 * 2. Вставляет HTML-кнопку в игровую панель настроек; клик по ней вызывает
 *    [openScoutSettings], который открывает экран настроек приложения.
 */
class ScoutBridge(
    private val onReady: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {

    @JavascriptInterface
    fun onGameReady() {
        onReady()
    }

    @JavascriptInterface
    fun openScoutSettings() {
        onOpenSettings()
    }

    companion object {
        const val JS_INTERFACE_NAME = "__sbg_scout"

        /**
         * Bootstrap-скрипт — MutationObserver на применение переводов i18next.
         *
         * Сигнал готовности: `#settings` (кнопка в .ol-control) получил перевод —
         * его textContent перестал быть ключом `menu.settings`. К этому моменту
         * `.settings-content` уже заполнен, и HTML-кнопку можно вставлять
         * с локализованными строками.
         *
         * Запуск откладывается до DOMContentLoaded: evaluateJavascript в
         * onPageStarted выполняется до того как реальный documentElement создан,
         * observer на несуществующем/временном documentElement мутаций не ловит.
         *
         * Локализация строк: читается `localStorage['settings'].lang`; если язык
         * начинается с "ru" — берутся русские строки, иначе английские.
         *
         * Нет таймаута: если игра не загрузилась (нет сети) — HTML-кнопка не
         * инжектируется, нативная кнопка остаётся единственным способом
         * открыть настройки SBG Scout.
         */
        val BOOTSTRAP_SCRIPT = """
            (function() {
                var STRINGS = {
                    ru: { section: 'SBG Scout', item: 'Настройки приложения', button: 'Открыть' },
                    en: { section: 'SBG Scout', item: 'App settings', button: 'Open' }
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
                    if (document.getElementById('sbg-scout-section')) return;
                    var content = document.querySelector('.settings-content');
                    if (!content) return;
                    var strings = pickStrings();
                    var section = document.createElement('div');
                    section.className = 'settings-section';
                    section.id = 'sbg-scout-section';
                    var header = document.createElement('h4');
                    header.className = 'settings-section__header';
                    header.textContent = strings.section;
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
                    section.appendChild(header);
                    section.appendChild(item);
                    content.insertBefore(section, content.firstChild);
                }

                function checkReady() {
                    var settingsBtn = document.querySelector('#settings');
                    if (!settingsBtn) return false;
                    var text = (settingsBtn.textContent || '').trim();
                    if (!text || text === 'menu.settings') return false;
                    return true;
                }

                function onReady() {
                    if (window.__sbg_scout) window.__sbg_scout.onGameReady();
                    injectButton();
                }

                function start() {
                    if (window.__sbg_scout_bootstrapped) return;
                    window.__sbg_scout_bootstrapped = true;
                    if (checkReady()) {
                        onReady();
                        return;
                    }
                    var observer = new MutationObserver(function() {
                        if (checkReady()) {
                            observer.disconnect();
                            onReady();
                        }
                    });
                    observer.observe(document.documentElement, {
                        childList: true, subtree: true, characterData: true
                    });
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
