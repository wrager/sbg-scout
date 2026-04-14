package com.github.wrager.sbgscout.webview

/**
 * JS-патч, переопределяющий `navigator.userAgent` внутри WebView так, чтобы в
 * возвращаемой строке не было токена `wv`. Временный workaround для проверки
 * `IsWebView` в EUI (`navigator.userAgent.toLowerCase().includes('wv')`), которая
 * отключает импорт/экспорт скриптов. Подмена выполняется только на JS-уровне:
 * реальный `webView.settings.userAgentString` и HTTP-заголовки запросов не
 * меняются, сервер по-прежнему получает настоящий UA с суффиксом `SbgScout/…`.
 *
 * Инжектируется первым в `onPageStarted` — до остальных bootstrap-скриптов и
 * до пользовательских юзерскриптов, чтобы к моменту чтения UA геттер уже был
 * перехвачен. Идемпотентен: повторная инжекция не выполняется благодаря флагу
 * `window.__sbg_ua_override_installed`.
 *
 * Откатить после правки проверки `IsWebView` в EUI.
 */
internal object UserAgentOverride {
    val BOOTSTRAP_SCRIPT =
        """
        (function() {
          if (window.__sbg_ua_override_installed) return;
          window.__sbg_ua_override_installed = true;
          try {
            var patched = navigator.userAgent.replace(/; wv/i, '');
            Object.defineProperty(navigator, 'userAgent', {
              configurable: true,
              get: function() { return patched; },
            });
          } catch (e) {}
        })();
        """.trimIndent()
}
