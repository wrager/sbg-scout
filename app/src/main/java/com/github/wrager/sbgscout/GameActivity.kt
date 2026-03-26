package com.github.wrager.sbgscout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.github.wrager.sbgscout.bridge.ClipboardBridge
import com.github.wrager.sbgscout.bridge.GameSettingsBridge
import com.github.wrager.sbgscout.bridge.ShareBridge
import com.github.wrager.sbgscout.diagnostic.ConsoleLogBuffer
import com.github.wrager.sbgscout.game.GameSettingsReader
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.launcher.ScriptListFragment
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.injector.ScriptInjector
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.storage.ScriptStorageImpl
import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.script.updater.PendingScriptUpdateStorage
import com.github.wrager.sbgscout.script.updater.ScriptReleaseNotesProvider
import com.github.wrager.sbgscout.script.updater.ScriptUpdateChecker
import com.github.wrager.sbgscout.script.updater.ScriptUpdateResult
import com.github.wrager.sbgscout.script.installer.BundledScriptInstaller
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import com.github.wrager.sbgscout.updater.AppUpdateChecker
import com.github.wrager.sbgscout.updater.AppUpdateInstaller
import com.github.wrager.sbgscout.updater.AppUpdateResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.wrager.sbgscout.webview.SbgWebViewClient
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Activity объединяет WebView, drawer, provisioning и обновления — разбивать на части нецелесообразно
@Suppress("TooManyFunctions")
class GameActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var scriptStorage: ScriptStorage
    val consoleLogBuffer = ConsoleLogBuffer()
    private lateinit var scriptProvisioner: DefaultScriptProvisioner
    private var injectionStateStorage: InjectionStateStorage? = null
    private var isFullscreen = false
    private val gameSettingsReader = GameSettingsReader()
    private var lastAppliedTheme: GameSettingsReader.ThemeMode? = null
    private var lastAppliedLanguage: String? = null

    /** Применяет настройки немедленно при переключении в UI (без ожидания закрытия drawer). */
    private val preferenceChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                KEY_FULLSCREEN_MODE -> applyFullscreen(prefs.getBoolean(key, false))
                KEY_KEEP_SCREEN_ON -> applyKeepScreenOn(prefs.getBoolean(key, true))
                KEY_PULL_TAB_POSITION -> applyPullTabPosition(
                    prefs.getInt(key, DEFAULT_PULL_TAB_POSITION),
                )
            }
        }

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
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Применить тему/язык ДО super.onCreate, чтобы Activity
        // создалась сразу с правильной конфигурацией (без вспышки)
        restoreLastAppliedGameSettings(prefs)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_game)
        rootLayout = findViewById(R.id.rootLayout)
        webView = findViewById(R.id.gameWebView)
        setupWindowInsets()

        applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        setupWebView()
        setupBackPressHandling()
        setupSettingsDrawer()
        scheduleAutoUpdateCheck(prefs)

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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // При configChanges Activity не пересоздаётся, поэтому фон окна
        // (windowBackground) и вью с ?android:colorBackground остаются от старой
        // конфигурации. Перечитываем актуальные значения из темы.
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
        window.setBackgroundDrawableResource(typedValue.resourceId)
        theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
        @Suppress("ResourceType") // colorBackground — цвет, не drawable
        val backgroundColor = ContextCompat.getColor(this, typedValue.resourceId)
        findViewById<View>(R.id.settingsContainer)?.setBackgroundColor(backgroundColor)

        // Пересоздать SettingsFragment для применения новой темы/локали.
        // WebView не затрагивается — он обрабатывает configChanges самостоятельно.
        if (supportFragmentManager.backStackEntryCount == 0) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commitAllowingStateLoss()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            applyFullscreen(prefs.getBoolean(KEY_FULLSCREEN_MODE, false))
            applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
            if (prefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false)) {
                prefs.edit().remove(LauncherActivity.KEY_RELOAD_REQUESTED).apply()
                webView.loadUrl(GAME_URL)
            }
        }
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onDestroy()
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

    /**
     * Восстанавливает и применяет последние настройки игры из SharedPreferences.
     *
     * Вызывается в onCreate до setContentView, чтобы:
     * 1. Применить тему/язык сразу (без вспышки дефолтной темы)
     * 2. Инициализировать lastAppliedTheme/lastAppliedLanguage, чтобы повторное
     *    чтение тех же значений из onPageFinished было no-op
     *
     * GameActivity обрабатывает uiMode/locale через configChanges, поэтому
     * setDefaultNightMode/setApplicationLocales не вызывают recreation.
     */
    private fun restoreLastAppliedGameSettings(prefs: android.content.SharedPreferences) {
        prefs.getString(KEY_APPLIED_GAME_THEME, null)?.let { themeName ->
            val theme = try {
                GameSettingsReader.ThemeMode.valueOf(themeName)
            } catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
                null
            }
            if (theme != null) {
                lastAppliedTheme = theme
                val nightMode = when (theme) {
                    GameSettingsReader.ThemeMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    GameSettingsReader.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    GameSettingsReader.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                }
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
        }
        prefs.getString(KEY_APPLIED_GAME_LANGUAGE, null)?.let { language ->
            lastAppliedLanguage = language
            val locales = if (language == "sys") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun applyGameSettings(json: String?) {
        val settings = gameSettingsReader.parse(json) ?: return
        applyGameTheme(settings.theme)
        applyGameLanguage(settings.language)
    }

    private fun applyGameTheme(theme: GameSettingsReader.ThemeMode) {
        if (theme == lastAppliedTheme) return
        lastAppliedTheme = theme
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString(KEY_APPLIED_GAME_THEME, theme.name).apply()
        val nightMode = when (theme) {
            GameSettingsReader.ThemeMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            GameSettingsReader.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            GameSettingsReader.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun applyGameLanguage(language: String) {
        if (language == lastAppliedLanguage) return
        lastAppliedLanguage = language
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString(KEY_APPLIED_GAME_LANGUAGE, language).apply()
        val locales = if (language == "sys") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    /**
     * Применяет вертикальное положение pull-tab (0–100% высоты экрана).
     * Обновляет позицию самого таба и зону касания drawer.
     */
    private fun applyPullTabPosition(percent: Int) {
        val pullTab = findViewById<SettingsPullTab>(R.id.settingsPullTab) ?: return
        val drawerLayout = findViewById<SettingsDrawerLayout>(R.id.settingsDrawer) ?: return
        val tabY = rootLayout.height * (percent / 100f)
        pullTab.y = tabY - pullTab.height / 2f
        drawerLayout.tabCenterY = tabY
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
        val settingsBridge = GameSettingsBridge { json ->
            runOnUiThread { applyGameSettings(json) }
        }
        webView.addJavascriptInterface(settingsBridge, GameSettingsBridge.JS_INTERFACE_NAME)

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
        injectionStateStorage = InjectionStateStorage(preferences)
        val scriptInjector = ScriptInjector(
            scriptStorage = scriptStorage,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            injectionStateStorage = injectionStateStorage,
        )
        val webViewClient = SbgWebViewClient(scriptInjector)
        webViewClient.onGameSettingsRead = { json -> applyGameSettings(json) }
        webView.webViewClient = webViewClient

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                consoleLogBuffer.add(consoleMessage)
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            applyPullTabPosition(prefs.getInt(KEY_PULL_TAB_POSITION, DEFAULT_PULL_TAB_POSITION))
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
                pullTab.translationX = -slideOffset * drawerView.width
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

    /** Список включённых скриптов (для диагностики баг-репортов). */
    fun getEnabledScripts() = scriptStorage.getEnabled()

    /** Все установленные скрипты (для баг-репорта — показать с маркерами статуса). */
    fun getAllScripts() = scriptStorage.getAll()

    /** Снапшот скриптов, инжектированных при последней загрузке страницы. */
    fun getInjectedSnapshot() = injectionStateStorage?.getSnapshot()

    /** Закрыть drawer настроек (вызывается из фрагментов внутри drawer). */
    fun closeSettingsDrawer() {
        findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
            .closeDrawer(GravityCompat.END)
    }

    /** Выполнить отложенные действия при закрытии drawer (перезагрузка игры). */
    private fun applySettingsAfterDrawerClose() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
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
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)

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
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)
        webView.loadUrl(GAME_URL)
    }

    /**
     * Запускает фоновую проверку обновлений приложения и скриптов, если:
     * - авто-проверка включена в настройках
     * - прошло больше 24 часов с последней проверки
     */
    private fun scheduleAutoUpdateCheck(prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)) return
        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
        val now = System.currentTimeMillis()
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS) return

        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
        val httpFetcher = DefaultHttpFetcher()
        lifecycleScope.launch {
            // Параллельная проверка приложения и скриптов
            var appResult: AppUpdateResult = AppUpdateResult.UpToDate
            try {
                appResult = AppUpdateChecker(
                    GithubReleaseProvider(httpFetcher),
                    BuildConfig.VERSION_NAME,
                ).check()
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                Log.w(LOG_TAG, "Авто-проверка обновлений приложения: ошибка", exception)
            }
            val scriptUpdates = checkScriptUpdates()

            // Показать диалоги: сначала приложение, при закрытии — скрипты
            if (appResult is AppUpdateResult.UpdateAvailable) {
                Log.i(LOG_TAG, "Доступно обновление приложения: ${appResult.tagName}")
                showAppUpdateDialog(
                    appResult.downloadUrl, appResult.releaseNotes, httpFetcher,
                ) {
                    if (scriptUpdates.isNotEmpty()) showScriptUpdatesDialog(scriptUpdates)
                }
            } else {
                if (appResult is AppUpdateResult.CheckFailed) {
                    Log.w(LOG_TAG, "Не удалось проверить обновление приложения", appResult.error)
                }
                if (scriptUpdates.isNotEmpty()) showScriptUpdatesDialog(scriptUpdates)
            }
        }
    }

    /** Показывает диалог обновления приложения (используется и авто-проверкой, и кнопкой в настройках). */
    fun showAppUpdateDialog(
        downloadUrl: String,
        releaseNotes: String?,
        httpFetcher: DefaultHttpFetcher,
        onDismiss: (() -> Unit)? = null,
    ) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_available)
        if (!releaseNotes.isNullOrBlank()) {
            val density = resources.displayMetrics.density
            val maxHeightPx = (RELEASE_NOTES_MAX_HEIGHT_DP * density).toInt()
            val paddingPx = (RELEASE_NOTES_PADDING_DP * density).toInt()
            val textView = TextView(this).apply {
                text = releaseNotes.trim()
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                setTextIsSelectable(true)
            }
            val scrollView = android.widget.ScrollView(this).apply {
                addView(textView)
            }
            // FrameLayout с ограничением максимальной высоты,
            // чтобы короткие release notes не растягивали диалог
            val container = object : FrameLayout(this@GameActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val constrainedHeight = View.MeasureSpec.makeMeasureSpec(
                        maxHeightPx, View.MeasureSpec.AT_MOST,
                    )
                    super.onMeasure(widthMeasureSpec, constrainedHeight)
                }
            }
            container.addView(scrollView)
            builder.setView(container)
        }
        builder
            .setPositiveButton(R.string.app_update_download) { _, _ ->
                showAppDownloadProgressDialog(downloadUrl, httpFetcher)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
    }

    private fun showAppDownloadProgressDialog(
        downloadUrl: String,
        httpFetcher: DefaultHttpFetcher,
    ) {
        val density = resources.displayMetrics.density
        val paddingPx = (RELEASE_NOTES_PADDING_DP * density).toInt()
        val progressIndicator = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_downloading)
            .setView(progressIndicator)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val installer = AppUpdateInstaller(applicationContext, httpFetcher)
        lifecycleScope.launch {
            try {
                installer.downloadAndInstall(downloadUrl) { progress ->
                    runOnUiThread {
                        if (progress > 0) {
                            progressIndicator.isIndeterminate = false
                            progressIndicator.progress = progress
                        }
                    }
                }
                dialog.dismiss()
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                dialog.dismiss()
                Toast.makeText(
                    this@GameActivity,
                    getString(R.string.app_update_download_failed, exception.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Проверяет обновления скриптов и загружает release notes для каждого.
     *
     * @return пары (обновление, release notes) или пустой список при ошибке
     */
    private suspend fun checkScriptUpdates(): List<ScriptUpdateWithNotes> {
        return try {
            val httpFetcher = DefaultHttpFetcher()
            val scriptChecker = ScriptUpdateChecker(httpFetcher, scriptStorage)
            val results = scriptChecker.checkAllForUpdates()
            val available = results.filterIsInstance<ScriptUpdateResult.UpdateAvailable>()
            if (available.isEmpty()) {
                Log.d(LOG_TAG, "Все скрипты актуальны")
                return emptyList()
            }
            Log.i(LOG_TAG, "Доступны обновления скриптов: ${available.size}")

            val notesProvider = ScriptReleaseNotesProvider(GithubReleaseProvider(httpFetcher))
            val scripts = scriptStorage.getAll()
            available.map { update ->
                val script = scripts.find { it.identifier == update.identifier }
                val notes = script?.sourceUrl?.let { sourceUrl ->
                    try {
                        notesProvider.fetchReleaseNotes(sourceUrl, update.currentVersion)
                    } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                        Log.w(LOG_TAG, "Не удалось загрузить release notes для ${update.identifier}", exception)
                        null
                    }
                }
                ScriptUpdateWithNotes(update, notes)
            }
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            Log.w(LOG_TAG, "Авто-проверка обновлений скриптов: ошибка", exception)
            emptyList()
        }
    }

    private fun showScriptUpdatesDialog(updates: List<ScriptUpdateWithNotes>) {
        val scripts = scriptStorage.getAll()
        val details = buildString {
            for ((index, item) in updates.withIndex()) {
                if (index > 0) append("\n\n")
                val name = scripts.find { it.identifier == item.update.identifier }?.header?.name
                    ?: item.update.identifier.value
                append("$name ${item.update.currentVersion.value} \u2192 ${item.update.latestVersion.value}")
                if (item.releaseNotes != null) {
                    append("\n")
                    append(item.releaseNotes)
                }
            }
        }

        val density = resources.displayMetrics.density
        val maxHeightPx = (RELEASE_NOTES_MAX_HEIGHT_DP * density).toInt()
        val paddingPx = (RELEASE_NOTES_PADDING_DP * density).toInt()
        val textView = TextView(this).apply {
            text = details
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setTextIsSelectable(true)
        }
        val scrollView = android.widget.ScrollView(this).apply { addView(textView) }
        val container = object : FrameLayout(this@GameActivity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val constrainedHeight = View.MeasureSpec.makeMeasureSpec(
                    maxHeightPx, View.MeasureSpec.AT_MOST,
                )
                super.onMeasure(widthMeasureSpec, constrainedHeight)
            }
        }
        container.addView(scrollView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.script_updates_available)
            .setView(container)
            .setPositiveButton(R.string.update) { _, _ ->
                // Сохраняем описание обновлений для показа при следующем открытии лаунчера
                val pendingStorage = PendingScriptUpdateStorage(
                    getSharedPreferences("scripts", MODE_PRIVATE),
                )
                pendingStorage.save(details)
                openScriptManagerWithAutoUpdate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openScriptManagerWithAutoUpdate() {
        val drawerLayout = findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
        drawerLayout.openDrawer(GravityCompat.END)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, ScriptListFragment.newEmbeddedAutoUpdateInstance())
            .addToBackStack(null)
            .commit()
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val drawerLayout = findViewById<SettingsDrawerLayout>(R.id.settingsDrawer)
                    val drawerOpen = drawerLayout.isDrawerOpen(GravityCompat.END)
                    when {
                        // ScriptListFragment открыт в drawer → вернуться к SettingsFragment
                        drawerOpen && supportFragmentManager.backStackEntryCount > 0 -> {
                            supportFragmentManager.popBackStack()
                        }
                        // Drawer открыт на SettingsFragment → закрыть drawer
                        drawerOpen -> {
                            drawerLayout.closeDrawer(GravityCompat.END)
                        }
                        // Drawer закрыт → стандартная обработка WebView
                        else -> {
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
                    }
                }
            },
        )
    }

    private data class ScriptUpdateWithNotes(
        val update: ScriptUpdateResult.UpdateAvailable,
        val releaseNotes: String?,
    )

    companion object {
        private const val GAME_URL = "https://sbg-game.ru/app"
        private const val LOG_TAG = "SbgWebView"
        private const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_APPLIED_GAME_THEME = "applied_game_theme"
        private const val KEY_APPLIED_GAME_LANGUAGE = "applied_game_language"
        private const val KEY_PULL_TAB_POSITION = "pull_tab_position"
        private const val DEFAULT_PULL_TAB_POSITION = 75
        private const val DEFAULT_DRAWER_WIDTH_DP = 300f
        private const val DRAWER_GAP_DIVISOR = 3
        private const val SKIP_BUTTON_CONNECT_DELAY_MS = 2_000L
        private const val SKIP_BUTTON_DOWNLOAD_DELAY_MS = 5_000L
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RELEASE_NOTES_MAX_HEIGHT_DP = 200
        private const val RELEASE_NOTES_PADDING_DP = 24
    }
}
