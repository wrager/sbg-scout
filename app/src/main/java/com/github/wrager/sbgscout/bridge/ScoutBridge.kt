package com.github.wrager.sbgscout.bridge

import android.webkit.JavascriptInterface

/**
 * JS-бридж для взаимодействия кнопки «Scout» с игрой.
 *
 * Bootstrap-скрипт, инжектируемый в onPageStarted, ждёт полной загрузки игры
 * (см. [BOOTSTRAP_SCRIPT]) и:
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
         * Bootstrap-скрипт — polling за готовностью игры.
         *
         * Игра готова, когда одновременно:
         * - инициализирован i18next (`window.i18next.isInitialized`);
         * - игра убрала свой экран загрузки `.loading-screen`. Игра удаляет его,
         *   только когда все этапы загрузки (тексты, данные, вход в аккаунт,
         *   инвентарь, интерфейс, геолокация) прошли успешно или игрок нажал
         *   «Хочу», не дожидаясь геолокации. При ошибке этапа экран остаётся —
         *   остаются и подложка с «Reload»;
         * - нет `.fatal-error`: при фатальной ошибке (например, отказе
         *   в геолокации) игра заменяет страницу сообщением, и экран загрузки
         *   пропадает вместе с ней.
         *
         * i18next инициализируется на втором этапе загрузки, поэтому раньше
         * подложка снималась, даже если игра потом падала на входе в аккаунт.
         * Проверка i18next оставлена: если экран загрузки когда-нибудь пропадёт
         * из разметки, подложка всё равно дождётся хотя бы i18next. Экран загрузки
         * скрипты, переделывающие UI (CUI, EUI), не удаляют — SVP меняет только
         * строку версии внутри него, EUI лишь стилизует `.fatal-error`.
         * Сигнал по-прежнему не зависит от игрового интерфейса: версия, которая
         * смотрела на `#settings` textContent, не срабатывала под CUI.
         *
         * Запуск откладывается до DOMContentLoaded: evaluateJavascript в
         * onPageStarted выполняется до того как реальный document создан.
         *
         * Локализация HTML-кнопки: читается `localStorage['settings'].lang`;
         * при значении `sys` (или пустом) язык резолвится через `navigator.language`
         * — так же, как делает сама игра в `script.js` через `getLanguage()`.
         * Если итоговый язык начинается с "ru" — русские строки, иначе английские.
         *
         * Нет таймаута: polling продолжается, пока игра не загрузится.
         * Если этого не случится (нет сети, ошибка этапа) — подложка загрузки
         * не снимается, нативные «Scout» и «Reload» остаются доступны.
         */
        val BOOTSTRAP_SCRIPT = """
            (function() {
                var STRINGS = {
                    ru: { item: 'Настройки SBG Scout', button: 'Открыть' },
                    en: { item: 'SBG Scout settings', button: 'Open' }
                };

                function pickStrings() {
                    try {
                        var lang = '';
                        var raw = localStorage.getItem('settings');
                        if (raw) {
                            var parsed = JSON.parse(raw);
                            lang = (parsed && parsed.lang) || '';
                        }
                        if (lang === '' || lang === 'sys') {
                            lang = navigator.language || '';
                        }
                        if (lang.indexOf('ru') === 0) return STRINGS.ru;
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
                    if (!(window.i18next && window.i18next.isInitialized)) return false;
                    if (document.querySelector('.loading-screen')) return false;
                    return !document.querySelector('.fatal-error');
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
