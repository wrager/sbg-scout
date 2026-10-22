package com.github.wrager.sbgscout.webview

import android.webkit.WebView
import com.github.wrager.sbgscout.config.GameUrls

/**
 * Системная кнопка "назад" на странице игры. Сначала нажатие получает страница:
 * отменяемое событие `backbutton` для юзерскриптов и закрытие верхнего игрового
 * попапа (чат, инвентарь, точка). Нажатие, которое страница не использовала,
 * возвращает WebView по истории, а без истории вызывает [exit].
 *
 * 🤖 Generated with Claude Fable 5.1
 */
class GameBackNavigation(
    private val webView: WebView,
    private val exit: () -> Unit,
) {
    fun onBackPressed() {
        webView.evaluateJavascript(BACK_PRESS_SCRIPT) { result ->
            if (result != HANDLED_RESULT) goBackOrExit()
        }
    }

    private fun goBackOrExit() {
        if (canGoBackWithinGame()) webView.goBack() else exit()
    }

    /**
     * Запись входа перед текущей страницей считается концом истории: возврат
     * на страницу входа с живой сессией разлогинивает игрока.
     * 🤖 Generated with Claude Fable 5.1
     */
    private fun canGoBackWithinGame(): Boolean {
        if (!webView.canGoBack()) return false
        val history = webView.copyBackForwardList()
        val previous = history.getItemAtIndex(history.currentIndex - 1) ?: return true
        return !GameUrls.isLoginPage(previous.url)
    }

    companion object {
        /** `evaluateJavascript` отдаёт строковый результат скрипта в JSON-кавычках. */
        private const val HANDLED_RESULT = "\"handled\""

        /**
         * Скрипт нажатия "назад" в документе игры; результат `handled`
         * или `unhandled`.
         *
         * Открытые попапы считаются до события: CUI закрывает попап точки
         * и профиля в своём слушателе `backbutton`, не отменяя событие, и это
         * тоже использованное нажатие. EUI отменяет событие сам.
         *
         * Попап закрывается его собственной кнопкой `.popup-close`, чтобы
         * отработал `closePopup` игры (таймеры, popover'ы). Кнопка вложенного
         * попапа (сортировка инвентаря) не подходит: она закрывает только его.
         * Без кнопки (levelup, score) попап прячется классом `hidden`, как
         * делает сама игра. Экран загрузки игры попапом не считается.
         *
         * 🤖 Generated with Claude Fable 5.1
         */
        val BACK_PRESS_SCRIPT =
            """
            (function () {
                function isOpenPopup(popup) {
                    return !popup.classList.contains('loading-screen') &&
                        popup.closest('.popup.hidden') === null;
                }
                function closePopup(popup) {
                    var buttons = popup.querySelectorAll('.popup-close');
                    for (var i = 0; i < buttons.length; i++) {
                        if (buttons[i].closest('.popup') === popup) {
                            buttons[i].click();
                            return;
                        }
                    }
                    popup.classList.add('hidden');
                }
                var openPopups = Array.prototype.filter.call(
                    document.querySelectorAll('.popup:not(.hidden)'),
                    isOpenPopup
                );
                var event = new Event('backbutton', { cancelable: true });
                document.dispatchEvent(event);
                if (event.defaultPrevented) return 'handled';
                if (openPopups.length === 0) return 'unhandled';
                var topmost = openPopups[openPopups.length - 1];
                if (document.contains(topmost) && isOpenPopup(topmost)) closePopup(topmost);
                return 'handled';
            })();
            """.trimIndent()
    }
}
