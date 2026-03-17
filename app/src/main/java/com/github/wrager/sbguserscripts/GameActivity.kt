package com.github.wrager.sbguserscripts

import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.wrager.sbguserscripts.bridge.ClipboardBridge
import com.github.wrager.sbguserscripts.bridge.ShareBridge
import com.github.wrager.sbguserscripts.webview.SbgWebViewClient

class GameActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        webView = findViewById(R.id.gameWebView)
        enableImmersiveMode()
        setupWebView()
        setupBackPressHandling()

        if (savedInstanceState == null) {
            webView.loadUrl(GAME_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupWebView() {
        @Suppress("SetJavaScriptEnabled") // JS обязателен для работы SBG
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)

        webView.addJavascriptInterface(ClipboardBridge(this), "Android")
        webView.addJavascriptInterface(ShareBridge(this), "__sbg_share")
        webView.webViewClient = SbgWebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                // Runtime-разрешение добавлено в коммите 10; здесь разрешаем всегда
                callback.invoke(origin, true, false)
            }
        }
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    webView.evaluateJavascript(
                        "document.dispatchEvent(new Event('backbutton'))",
                    ) {}
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    companion object {
        private const val GAME_URL = "https://sbg-game.ru/app/"
    }
}
