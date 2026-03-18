package com.github.wrager.sbguserscripts.webview

import android.app.Activity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class SbgWebViewClient : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url?.contains("sbg-game.ru/app") == true) {
            injectClipboardPolyfill(view)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (url.contains("window.close")) {
            val context = view?.context
            if (context is Activity) context.finish()
            return true
        }
        // Все URL (включая Telegram OAuth) загружаются в WebView
        return false
    }

    private fun injectClipboardPolyfill(view: WebView?) {
        val polyfill = """
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
        view?.evaluateJavascript(polyfill) {}
    }
}
