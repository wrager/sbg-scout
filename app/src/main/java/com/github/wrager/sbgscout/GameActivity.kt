package com.github.wrager.sbgscout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.TextView
import android.view.View
import androidx.core.view.doOnLayout
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import androidx.core.view.GravityCompat
import com.github.wrager.sbgscout.game.SettingsDrawerLayout
import com.github.wrager.sbgscout.game.SettingsPullTab
import com.github.wrager.sbgscout.settings.SettingsFragment
import com.github.wrager.sbgscout.bridge.ClipboardBridge
import com.github.wrager.sbgscout.bridge.ShareBridge
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.injector.ScriptInjector
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.storage.ScriptStorageImpl
import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher
import com.github.wrager.sbgscout.script.installer.BundledScriptInstaller
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import com.github.wrager.sbgscout.webview.SbgWebViewClient
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GameActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var scriptStorage: ScriptStorage
    private lateinit var scriptProvisioner: DefaultScriptProvisioner
    private var isFullscreen = false

    // Pending geolocation callback while waiting for Android permission result
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, true, false)
        } else {
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, false, false)
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
        }

        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_game)
        rootLayout = findViewById(R.id.rootLayout)
        webView = findViewById(R.id.gameWebView)
        setupWindowInsets()

        setupWebView()
        setupBackPressHandling()
        setupSettingsDrawer()

        if (savedInstanceState == null) {
            if (scriptProvisioner.hasPendingScripts()) {
                startProvisioning()
            } else {
                webView.loadUrl(GAME_URL)
            }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            applyFullscreen(prefs.getBoolean(KEY_FULLSCREEN_MODE, false))
            if (prefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false)) {
                prefs.edit().remove(LauncherActivity.KEY_RELOAD_REQUESTED).apply()
                webView.loadUrl(GAME_URL)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    private fun setupWindowInsets() {
        isFullscreen = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(KEY_FULLSCREEN_MODE, false)

        // Edge-to-edge всегда включён (обязательно на Android 15+).
        // В неполноэкранном режиме сдвигаем корневой layout паддингами,
        // чтобы WebView не залезал под системные бары.
        // IME-инсеты учитываются всегда, чтобы клавиатура не перекрывала input.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            if (isFullscreen) {
                view.setPadding(0, 0, 0, imeInsets.bottom)
            } else {
                val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    barInsets.left,
                    barInsets.top,
                    barInsets.right,
                    maxOf(barInsets.bottom, imeInsets.bottom),
                )
            }
            windowInsets
        }
    }

    private fun applyFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        // Перезапросить insets для обновления паддингов корневого layout
        ViewCompat.requestApplyInsets(rootLayout)
    }

    private fun configureCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    private fun setupWebView() {
        configureCookies()

        @Suppress("SetJavaScriptEnabled") // JS обязателен для работы SBG
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.addJavascriptInterface(ClipboardBridge(this), "Android")
        webView.addJavascriptInterface(ShareBridge(this), "__sbg_share")

        val preferences = getSharedPreferences("scripts", MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(filesDir, "scripts"))
        scriptStorage = ScriptStorageImpl(preferences, fileStorage)
        val httpFetcher = DefaultHttpFetcher()
        val scriptInstaller = ScriptInstaller(scriptStorage)
        val downloader = ScriptDownloader(httpFetcher, scriptInstaller)
        scriptProvisioner = DefaultScriptProvisioner(scriptStorage, downloader, preferences)
        BundledScriptInstaller(
            scriptInstaller, scriptStorage, scriptProvisioner,
            assetReader = { path -> assets.open(path).bufferedReader().readText() },
        ).installBundled()
        val injectionStateStorage = InjectionStateStorage(preferences)
        val scriptInjector = ScriptInjector(
            scriptStorage = scriptStorage,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            injectionStateStorage = injectionStateStorage,
        )
        webView.webViewClient = SbgWebViewClient(scriptInjector)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val logLevel = when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                    ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                    else -> Log.INFO
                }
                Log.println(
                    logLevel,
                    LOG_TAG,
                    "${consoleMessage.message()} [${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}]",
                )
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin = origin
                    locationPermissionRequest.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun setupSettingsDrawer() {
        val drawerLayout = findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
        val pullTab = findViewById<SettingsPullTab>(R.id.settingsPullTab)
        val settingsContainer = findViewById<View>(R.id.settingsContainer)

        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment())
            .commit()

        // Ширина drawer: видимая область за ним = 1/3 от стандартной (300dp)
        drawerLayout.doOnLayout {
            val screenWidth = drawerLayout.width
            val defaultDrawerWidth = (DEFAULT_DRAWER_WIDTH_DP * resources.displayMetrics.density).toInt()
            val defaultGap = screenWidth - defaultDrawerWidth
            val narrowGap = defaultGap / DRAWER_GAP_DIVISOR
            settingsContainer.layoutParams = settingsContainer.layoutParams.apply {
                width = screenWidth - narrowGap
            }
        }

        pullTab.doOnLayout {
            val tabY = rootLayout.height * PULL_TAB_VERTICAL_POSITION
            pullTab.y = tabY - pullTab.height / 2f
            drawerLayout.tabCenterY = tabY
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                // Скрыть клавиатуру при начале открытия drawer,
                // чтобы она не торчала поверх экрана настроек
                if (newState != DrawerLayout.STATE_IDLE) {
                    val imm = getSystemService(InputMethodManager::class.java)
                    currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
                }
            }

            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                pullTab.translationX = slideOffset * drawerView.width
            }

            override fun onDrawerOpened(drawerView: View) {
                pullTab.isOpen = true
            }

            override fun onDrawerClosed(drawerView: View) {
                pullTab.isOpen = false
                // При закрытии сбрасываем back stack (ScriptListFragment → SettingsFragment)
                val fragmentManager = supportFragmentManager
                if (fragmentManager.backStackEntryCount > 0) {
                    fragmentManager.popBackStackImmediate(
                        null,
                        FragmentManager.POP_BACK_STACK_INCLUSIVE,
                    )
                }
                applySettingsAfterDrawerClose()
            }
        })
    }

    /** Закрыть drawer настроек (вызывается из фрагментов внутри drawer). */
    fun closeSettingsDrawer() {
        findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
            .closeDrawer(GravityCompat.START)
    }

    /** Применить настройки, изменённые через drawer (аналог onWindowFocusChanged). */
    private fun applySettingsAfterDrawerClose() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        applyFullscreen(prefs.getBoolean(KEY_FULLSCREEN_MODE, false))
        if (prefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false)) {
            prefs.edit().remove(LauncherActivity.KEY_RELOAD_REQUESTED).apply()
            webView.loadUrl(GAME_URL)
        }
    }

    /**
     * Показывает оверлей загрузки, блокирует drawer и скрывает pull-tab,
     * затем запускает загрузку предустановленных скриптов.
     *
     * При успехе — скрывает оверлей и загружает игру.
     * При ошибке — показывает сообщение с кнопками «Повторить» / «Продолжить без скриптов».
     */
    private fun startProvisioning() {
        val overlay = findViewById<LinearLayout>(R.id.provisioningOverlay)
        val progress = findViewById<LinearProgressIndicator>(R.id.provisioningProgress)
        val status = findViewById<TextView>(R.id.provisioningStatus)
        val error = findViewById<TextView>(R.id.provisioningError)
        val retryButton = findViewById<Button>(R.id.provisioningRetryButton)
        val skipButton = findViewById<Button>(R.id.provisioningSkipButton)
        val drawerLayout = findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
        val pullTab = findViewById<SettingsPullTab>(R.id.settingsPullTab)

        overlay.visibility = View.VISIBLE
        pullTab.visibility = View.GONE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)

        // Сброс в состояние загрузки (актуально при повторных попытках)
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        status.setText(R.string.loading_default_scripts)
        status.visibility = View.VISIBLE
        error.visibility = View.GONE
        retryButton.visibility = View.GONE
        skipButton.visibility = View.INVISIBLE

        // Кнопка «Пропустить»: через 1с если соединение не установлено,
        // через 5с после начала загрузки данных
        var skipTimerJob = lifecycleScope.launch {
            delay(SKIP_BUTTON_CONNECT_DELAY_MS)
            skipButton.visibility = View.VISIBLE
            skipButton.setOnClickListener { finishProvisioning() }
        }

        lifecycleScope.launch {
            val success = scriptProvisioner.provision(
                onScriptLoading = { scriptName ->
                    progress.isIndeterminate = true
                    status.text = getString(R.string.loading_default_script, scriptName)
                },
                // Callback вызывается из Dispatchers.IO (внутри DefaultHttpFetcher),
                // поэтому UI-операции выполняем через runOnUiThread
                onDownloadProgress = { percent ->
                    runOnUiThread {
                        if (progress.isIndeterminate) {
                            progress.isIndeterminate = false
                            // Соединение установлено — перезапустить таймер на 5 секунд
                            skipTimerJob.cancel()
                            skipTimerJob = lifecycleScope.launch {
                                delay(SKIP_BUTTON_DOWNLOAD_DELAY_MS)
                                skipButton.visibility = View.VISIBLE
                                skipButton.setOnClickListener { finishProvisioning() }
                            }
                        }
                        progress.setProgressCompat(percent, true)
                    }
                },
            )
            skipTimerJob.cancel()
            if (success) {
                finishProvisioning()
            } else {
                showProvisioningError()
            }
        }
    }

    private fun showProvisioningError() {
        val progress = findViewById<LinearProgressIndicator>(R.id.provisioningProgress)
        val status = findViewById<TextView>(R.id.provisioningStatus)
        val error = findViewById<TextView>(R.id.provisioningError)
        val retryButton = findViewById<Button>(R.id.provisioningRetryButton)
        val skipButton = findViewById<Button>(R.id.provisioningSkipButton)

        progress.visibility = View.GONE
        status.visibility = View.GONE
        error.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        skipButton.visibility = View.VISIBLE

        retryButton.setOnClickListener { startProvisioning() }
        skipButton.setOnClickListener { finishProvisioning() }
    }

    private fun finishProvisioning() {
        val overlay = findViewById<LinearLayout>(R.id.provisioningOverlay)
        val drawerLayout = findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
        val pullTab = findViewById<SettingsPullTab>(R.id.settingsPullTab)

        overlay.visibility = View.GONE
        pullTab.visibility = View.VISIBLE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        webView.loadUrl(GAME_URL)
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
        private const val GAME_URL = "https://sbg-game.ru/app"
        private const val LOG_TAG = "SbgWebView"
        private const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
        private const val PULL_TAB_VERTICAL_POSITION = 0.25f
        private const val DEFAULT_DRAWER_WIDTH_DP = 300f
        private const val DRAWER_GAP_DIVISOR = 3
        private const val SKIP_BUTTON_CONNECT_DELAY_MS = 2_000L
        private const val SKIP_BUTTON_DOWNLOAD_DELAY_MS = 5_000L
    }
}
