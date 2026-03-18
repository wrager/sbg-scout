package com.github.wrager.sbguserscripts.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import com.github.wrager.sbguserscripts.BuildConfig

class SbgWebViewClient : WebViewClient() {

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        if (BuildConfig.DEBUG) {
            // Старые эмуляторы (API 24) не доверяют Let's Encrypt — разрешаем только в debug
            handler?.proceed()
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url?.contains("sbg-game.ru/app") == true) {
            injectClipboardPolyfill(view)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return when {
            url == "about:blank" -> {
                val context = view?.context
                if (context is Activity) context.finish()
                true
            }
            url.contains("sbg-game.ru") -> false
            else -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                view?.context?.startActivity(intent)
                true
            }
        }
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
