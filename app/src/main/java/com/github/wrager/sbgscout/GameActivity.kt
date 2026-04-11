package com.github.wrager.sbgscout

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.TextView
import android.view.View
import androidx.fragment.app.FragmentManager
import com.github.wrager.sbgscout.settings.SettingsFragment
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.github.wrager.sbgscout.bridge.ClipboardBridge
import com.github.wrager.sbgscout.bridge.DownloadBridge
import com.github.wrager.sbgscout.bridge.GameSettingsBridge
import com.github.wrager.sbgscout.bridge.ScoutBridge
import com.github.wrager.sbgscout.bridge.ShareBridge
import com.github.wrager.sbgscout.config.GameUrls
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
import androidx.appcompat.app.AlertDialog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Activity объединяет WebView, drawer, provisioning и обновления — разбивать на части нецелесообразно
@Suppress("TooManyFunctions", "LargeClass")
class GameActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout

    @VisibleForTesting
    internal lateinit var webView: WebView

    @VisibleForTesting
    internal lateinit var sbgWebViewClient: SbgWebViewClient
    private lateinit var scriptStorage: ScriptStorage
    val consoleLogBuffer = ConsoleLogBuffer()
    private lateinit var scriptProvisioner: DefaultScriptProvisioner
    private var injectionStateStorage: InjectionStateStorage? = null
    @VisibleForTesting
    internal var isFullscreen = false
        private set
    private val gameSettingsReader = GameSettingsReader()
    private var lastAppliedTheme: GameSettingsReader.ThemeMode? = null
    private var lastAppliedLanguage: String? = null

    /** true после того как i18next инициализирован — интерфейс игры готов. */
    private var gameReady = false

    /** true если HTML-кнопка в `.settings-content` успешно инжектирована (нативную «Scout» можно скрыть). */
    private var scoutButtonReplaced = false

    /** Применяет настройки немедленно при переключении в UI (без ожидания закрытия drawer). */
    private val preferenceChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                KEY_FULLSCREEN_MODE -> applyFullscreen(prefs.getBoolean(key, false))
                KEY_KEEP_SCREEN_ON -> applyKeepScreenOn(prefs.getBoolean(key, true))
            }
        }

    // Pending geolocation callback while waiting for Android permission result
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    // Pending callback для <input type="file"> — хранится между запуском пикера и onActivityResult
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingFileChooserCallback?.onReceiveValue(uris)
        pendingFileChooserCallback = null
    }

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
        setupSettings()
        scheduleAutoUpdateCheck(prefs)

        if (savedInstanceState == null) {
            if (scriptProvisioner.hasPendingScripts()) {
                startProvisioning()
            } else {
                webView.loadUrl(GameUrls.appUrl)
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
        findViewById<View>(R.id.closeSettingsFooterPanel)?.setBackgroundColor(backgroundColor)

        // Перечитать цвета кнопок «Scout»/«Reload»/«[x]» и сепаратора из текущей
        // темы (resources тянут значения из values-night при dark-режиме).
        refreshButtonColors()

        // Пересоздать SettingsFragment для применения новой темы/локали.
        // WebView не затрагивается — он обрабатывает configChanges самостоятельно.
        if (supportFragmentManager.backStackEntryCount == 0) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commitAllowingStateLoss()
        }
    }

    /**
     * Применяет цвета `scoutButton*` из текущей темы к кнопкам
     * «Scout»/«Reload»/«[x]» и текстовому label'у. Нужно после смены
     * uiMode (configChanges=uiMode), т.к. Activity не пересоздаётся и view'хи
     * держат значения цветов, разрезолвенные при инфляции.
     */
    private fun refreshButtonColors() {
        val background = ContextCompat.getColor(this, R.color.scoutButtonBackground)
        val content = ContextCompat.getColor(this, R.color.scoutButtonContent)
        val stroke = ContextCompat.getColor(this, R.color.scoutButtonStroke)
        val backgroundTint = android.content.res.ColorStateList.valueOf(background)
        val contentTint = android.content.res.ColorStateList.valueOf(content)
        val strokeTint = android.content.res.ColorStateList.valueOf(stroke)
        for (id in intArrayOf(R.id.settingsButton, R.id.reloadButton, R.id.closeSettingsButton)) {
            val button = findViewById<MaterialButton>(id) ?: continue
            button.backgroundTintList = backgroundTint
            button.setTextColor(content)
            button.iconTint = contentTint
            button.strokeColor = strokeTint
        }
        findViewById<android.widget.TextView>(R.id.gameInitializingLabel)?.setTextColor(content)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            applyFullscreen(prefs.getBoolean(KEY_FULLSCREEN_MODE, false))
            applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
            if (prefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false)) {
                prefs.edit().remove(LauncherActivity.KEY_RELOAD_REQUESTED).apply()
                webView.loadUrl(GameUrls.appUrl)
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

    private fun createScoutBridge(): ScoutBridge = ScoutBridge(
        onReady = {
            runOnUiThread {
                gameReady = true
                hideLoadingOverlay()
                hideLabelAndReload()
            }
        },
        onHtmlInjected = {
            runOnUiThread {
                scoutButtonReplaced = true
                hideScoutButton()
            }
        },
        onOpenSettings = { runOnUiThread { openSettings() } },
    )

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
        webView.settings.userAgentString =
            "${webView.settings.userAgentString} SbgScout/${BuildConfig.VERSION_NAME}"

        configureWebViewPerformance(webView)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.addJavascriptInterface(ClipboardBridge(this), "Android")
        webView.addJavascriptInterface(ShareBridge(this), "__sbg_share")
        webView.addJavascriptInterface(createDownloadBridge(), DownloadBridge.JS_INTERFACE_NAME)
        setupDownloadListener()
        val settingsBridge = GameSettingsBridge { json ->
            runOnUiThread { applyGameSettings(json) }
        }
        webView.addJavascriptInterface(settingsBridge, GameSettingsBridge.JS_INTERFACE_NAME)
        webView.addJavascriptInterface(createScoutBridge(), ScoutBridge.JS_INTERFACE_NAME)

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
        sbgWebViewClient = SbgWebViewClient(scriptInjector)
        sbgWebViewClient.onGameSettingsRead = { json -> applyGameSettings(json) }
        sbgWebViewClient.onGamePageStarted = {
            runOnUiThread {
                gameReady = false
                scoutButtonReplaced = false
                showLoadingButtons()
                showLoadingOverlay()
            }
        }
        webView.webViewClient = sbgWebViewClient

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

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // Отменить предыдущий незавершённый выбор, если он есть —
                // иначе WebView зависнет, ожидая ответа по старому callback.
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (@Suppress("SwallowedException") _: android.content.ActivityNotFoundException) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
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

    private fun createDownloadBridge(): DownloadBridge = DownloadBridge(
        save = { filename, mimeType, bytes -> saveToDownloads(filename, mimeType, bytes) },
        onResult = { savedName ->
            runOnUiThread {
                val message = if (savedName != null) {
                    getString(R.string.download_saved, savedName)
                } else {
                    getString(R.string.download_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        },
    )

    private fun setupDownloadListener() {
        // Blob-скачивания перехватываются на JS-уровне через DownloadBridge.BOOTSTRAP_SCRIPT
        // (см. комментарий к нему). DownloadListener остаётся как fallback-логгер
        // для неожиданных http/https-скачиваний — игра и юзерскрипты пока их не используют.
        webView.setDownloadListener { url, _, _, mimetype, _ ->
            Log.w(LOG_TAG, "Unhandled download: url=$url, mime=$mimetype")
        }
    }

    /**
     * Сохраняет байты в публичную директорию Downloads.
     * API 29+: MediaStore (без runtime permissions).
     * API < 29: app-specific external files dir (без permissions, но путь менее доступный).
     *
     * @return отображаемое имя сохранённого файла или null при ошибке
     */
    private fun saveToDownloads(filename: String, mimeType: String, bytes: ByteArray): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(filename, mimeType, bytes)
            } else {
                saveToLegacyExternalFiles(filename, bytes)
            }
        } catch (e: java.io.IOException) {
            Log.e(LOG_TAG, "Failed to save download: $filename", e)
            null
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Failed to save download: $filename", e)
            null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(filename: String, mimeType: String, bytes: ByteArray): String? {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return filename
    }

    private fun saveToLegacyExternalFiles(filename: String, bytes: ByteArray): String? {
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, filename)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun setupSettings() {
        val settingsContainer = findViewById<View>(R.id.settingsContainer)
        val settingsButton = findViewById<MaterialButton>(R.id.settingsButton)
        val reloadButton = findViewById<MaterialButton>(R.id.reloadButton)
        val closeButton = findViewById<MaterialButton>(R.id.closeSettingsButton)

        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment())
            .commit()

        settingsButton.setOnClickListener { openSettings() }
        reloadButton.setOnClickListener { webView.loadUrl(GameUrls.appUrl) }
        closeButton.setOnClickListener { closeSettings() }

        // Настройки — отдельный экран поверх WebView; закрытие программное
        // (плавающая кнопка [x] снизу по центру), свайп и back не используются.
        // Перехватываем клики, чтобы они не уходили в WebView под контейнером.
        settingsContainer.isClickable = true
    }

    /** Показать «Инициализация игры…» + «Scout» + «Reload» (на старте загрузки). */
    private fun showLoadingButtons() {
        if (isSettingsOpen()) return
        findViewById<View>(R.id.gameInitializingLabel).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.settingsButton).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.reloadButton).visibility = View.VISIBLE
    }

    /** Скрыть label + «Reload» (когда i18next инициализирован). «Scout» остаётся до onHtmlButtonInjected. */
    private fun hideLabelAndReload() {
        findViewById<View>(R.id.gameInitializingLabel).visibility = View.GONE
        findViewById<MaterialButton>(R.id.reloadButton).visibility = View.GONE
    }

    /** Скрыть «Scout» (когда HTML-кнопка инжектирована в игровую панель настроек). */
    private fun hideScoutButton() {
        findViewById<MaterialButton>(R.id.settingsButton).visibility = View.GONE
    }

    /** Показать белую подложку 50 % — на старте загрузки страницы игры. */
    private fun showLoadingOverlay() {
        findViewById<View>(R.id.loadingOverlay).visibility = View.VISIBLE
    }

    /** Скрыть белую подложку — когда интерфейс игры инициализировался. */
    private fun hideLoadingOverlay() {
        findViewById<View>(R.id.loadingOverlay).visibility = View.GONE
    }

    private fun openSettings() {
        findViewById<View>(R.id.settingsContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.topButtonsContainer).visibility = View.GONE
        findViewById<View>(R.id.closeSettingsFooter).visibility = View.VISIBLE
        // Скрыть клавиатуру, если была активна в WebView
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        // Сбросить скролл настроек: фрагмент живёт всё время, скролл
        // сохраняется между открытиями
        val settingsFragment = supportFragmentManager.findFragmentById(R.id.settingsContainer)
        if (settingsFragment is SettingsFragment) {
            settingsFragment.scrollToTop()
        }
    }

    private fun isSettingsOpen(): Boolean =
        findViewById<View>(R.id.settingsContainer).visibility == View.VISIBLE

    /** Список включённых скриптов (для диагностики баг-репортов). */
    fun getEnabledScripts() = scriptStorage.getEnabled()

    /** Все установленные скрипты (для баг-репорта — показать с маркерами статуса). */
    fun getAllScripts() = scriptStorage.getAll()

    /** Снапшот скриптов, инжектированных при последней загрузке страницы. */
    fun getInjectedSnapshot() = injectionStateStorage?.getSnapshot()

    /**
     * Закрыть экран настроек или вернуться на уровень выше.
     *
     * Если поверх `SettingsFragment` открыт фрагмент (например, `ScriptListFragment`),
     * возвращаемся к `SettingsFragment` через `popBackStack`. Иначе закрываем экран
     * настроек полностью и возвращаемся к WebView.
     */
    fun closeSettings() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
            return
        }
        dismissSettings()
    }

    /**
     * Полностью закрыть экран настроек — сбросить back stack (если открыт
     * фрагмент поверх SettingsFragment) и спрятать контейнер. Используется для
     * форсированного закрытия из фрагментов (например, кнопка «Reload game» в
     * ScriptListFragment после применения нового сетапа скриптов).
     */
    fun dismissSettings() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(
                null,
                FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        }
        findViewById<View>(R.id.settingsContainer).visibility = View.GONE
        findViewById<View>(R.id.topButtonsContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.closeSettingsFooter).visibility = View.GONE
        // Восстановить видимость loading-элементов по текущему состоянию:
        // — label и «Reload» скрываются, когда игра готова (i18next);
        // — «Scout» скрывается только если HTML-кнопка успешно инжектирована.
        //   При включённом CUI `.settings-content` отсутствует, HTML-кнопка не
        //   инжектируется, и «Scout» остаётся единственным способом открыть настройки.
        val loadingVisibility = if (gameReady) View.GONE else View.VISIBLE
        findViewById<View>(R.id.gameInitializingLabel).visibility = loadingVisibility
        findViewById<MaterialButton>(R.id.reloadButton).visibility = loadingVisibility
        findViewById<MaterialButton>(R.id.settingsButton).visibility =
            if (scoutButtonReplaced) View.GONE else View.VISIBLE
        applySettingsAfterClose()
    }

    /** Выполнить отложенные действия при закрытии настроек (перезагрузка игры). */
    private fun applySettingsAfterClose() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false)) {
            prefs.edit().remove(LauncherActivity.KEY_RELOAD_REQUESTED).apply()
            webView.loadUrl(GameUrls.appUrl)
        }
    }

    /**
     * Показывает оверлей загрузки, скрывает кнопку настроек,
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
        val topButtons = findViewById<View>(R.id.topButtonsContainer)

        overlay.visibility = View.VISIBLE
        topButtons.visibility = View.GONE

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
        val topButtons = findViewById<View>(R.id.topButtonsContainer)

        overlay.visibility = View.GONE
        topButtons.visibility = View.VISIBLE
        webView.loadUrl(GameUrls.appUrl)
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

    /**
     * Показывает диалог обновления приложения с результатом, полученным авто-проверкой.
     *
     * Используется [scheduleAutoUpdateCheck], когда обновление уже найдено.
     * Для ручной проверки из настроек используется [showAppUpdateCheckDialog].
     */
    fun showAppUpdateDialog(
        downloadUrl: String,
        releaseNotes: String?,
        httpFetcher: DefaultHttpFetcher,
        onDismiss: (() -> Unit)? = null,
    ) {
        val density = resources.displayMetrics.density
        val maxHeightPx = (RELEASE_NOTES_MAX_HEIGHT_DP * density).toInt()
        val paddingPx = (RELEASE_NOTES_PADDING_DP * density).toInt()

        val progressIndicator = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val releaseNotesContainer = FrameLayout(this)
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            addView(progressIndicator)
            addView(releaseNotesContainer)
        }
        if (!releaseNotes.isNullOrBlank()) {
            addReleaseNotesView(releaseNotesContainer, releaseNotes, maxHeightPx)
        }

        var activeJob: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_available)
            .setView(contentLayout)
            .setPositiveButton(R.string.app_update_download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                activeJob?.cancel()
                onDismiss?.invoke()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                activeJob = startAppDownload(
                    dialog, progressIndicator, releaseNotesContainer, downloadUrl, httpFetcher,
                )
            }
        }
        dialog.show()
    }

    /**
     * Единый диалог проверки и загрузки обновления приложения.
     *
     * Открывается сразу при нажатии «Проверить обновления приложения» в настройках.
     * Фазы: проверка (indeterminate) → результат / загрузка (determinate) → установка.
     * Cancel отменяет текущую операцию (проверку или загрузку).
     */
    // Метод длинный из-за управления фазами диалога (check → result → download) — дробить нецелесообразно
    @Suppress("LongMethod")
    fun showAppUpdateCheckDialog(onDismiss: (() -> Unit)? = null) {
        val httpFetcher = DefaultHttpFetcher()
        val density = resources.displayMetrics.density
        val paddingPx = (RELEASE_NOTES_PADDING_DP * density).toInt()
        val maxHeightPx = (RELEASE_NOTES_MAX_HEIGHT_DP * density).toInt()

        val progressIndicator = LinearProgressIndicator(this).apply {
            isIndeterminate = true
        }
        val releaseNotesContainer = FrameLayout(this)
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            addView(progressIndicator)
            addView(releaseNotesContainer)
        }

        var activeJob: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_checking)
            .setView(contentLayout)
            .setPositiveButton(R.string.app_update_download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                activeJob?.cancel()
                onDismiss?.invoke()
            }
            .create()

        dialog.setOnShowListener {
            val downloadButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            downloadButton.visibility = View.GONE

            activeJob = lifecycleScope.launch {
                try {
                    val checker = AppUpdateChecker(
                        GithubReleaseProvider(httpFetcher),
                        BuildConfig.VERSION_NAME,
                    )
                    when (val result = checker.check()) {
                        is AppUpdateResult.UpdateAvailable -> {
                            progressIndicator.visibility = View.GONE
                            dialog.setTitle(getString(R.string.app_update_available))
                            if (!result.releaseNotes.isNullOrBlank()) {
                                addReleaseNotesView(
                                    releaseNotesContainer, result.releaseNotes, maxHeightPx,
                                )
                            }
                            downloadButton.visibility = View.VISIBLE
                            downloadButton.setOnClickListener {
                                activeJob = startAppDownload(
                                    dialog, progressIndicator, releaseNotesContainer,
                                    result.downloadUrl, httpFetcher,
                                )
                            }
                        }
                        is AppUpdateResult.UpToDate -> {
                            progressIndicator.visibility = View.GONE
                            dialog.setTitle(getString(R.string.app_up_to_date))
                        }
                        is AppUpdateResult.CheckFailed -> {
                            progressIndicator.visibility = View.GONE
                            dialog.setTitle(getString(R.string.app_update_check_failed))
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                    Log.w(LOG_TAG, "Не удалось проверить обновление приложения", exception)
                    progressIndicator.visibility = View.GONE
                    dialog.setTitle(getString(R.string.app_update_check_failed))
                }
            }
        }
        dialog.show()
    }

    private fun addReleaseNotesView(
        container: FrameLayout,
        releaseNotes: String,
        maxHeightPx: Int,
    ) {
        val textView = TextView(this).apply {
            text = releaseNotes.trim()
            setTextIsSelectable(true)
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }
        // FrameLayout с ограничением максимальной высоты,
        // чтобы короткие release notes не растягивали диалог
        val heightLimitedContainer = object : FrameLayout(this@GameActivity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val constrainedHeight = View.MeasureSpec.makeMeasureSpec(
                    maxHeightPx, View.MeasureSpec.AT_MOST,
                )
                super.onMeasure(widthMeasureSpec, constrainedHeight)
            }
        }
        heightLimitedContainer.addView(scrollView)
        container.addView(heightLimitedContainer)
    }

    /**
     * Запускает загрузку APK внутри уже открытого диалога.
     *
     * Переключает диалог в режим загрузки: скрывает release notes и кнопку Download,
     * показывает прогресс-бар. Возвращает [Job] для отмены через Cancel.
     */
    private fun startAppDownload(
        dialog: AlertDialog,
        progressIndicator: LinearProgressIndicator,
        releaseNotesContainer: FrameLayout,
        downloadUrl: String,
        httpFetcher: DefaultHttpFetcher,
    ): Job {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
        releaseNotesContainer.removeAllViews()
        progressIndicator.isIndeterminate = true
        progressIndicator.visibility = View.VISIBLE
        dialog.setTitle(getString(R.string.app_update_downloading))

        val installer = AppUpdateInstaller(applicationContext, httpFetcher)
        return lifecycleScope.launch {
            try {
                var switchedToDeterminate = false
                installer.downloadAndInstall(downloadUrl) { progress ->
                    runOnUiThread {
                        if (progress > 0 && !switchedToDeterminate) {
                            switchedToDeterminate = true
                            // setProgressCompat корректно переключает indeterminate → determinate
                            progressIndicator.setProgressCompat(progress, true)
                        } else if (switchedToDeterminate) {
                            progressIndicator.setProgressCompat(progress, true)
                        }
                    }
                }
                dialog.dismiss()
            } catch (exception: CancellationException) {
                throw exception
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
        openSettings()
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
                    // На экране настроек «назад» игнорируется — закрытие только
                    // через плавающую кнопку [x] снизу по центру.
                    if (isSettingsOpen()) return
                    // «Назад» в игре идёт в WebView: навигация в истории или выход.
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

    private data class ScriptUpdateWithNotes(
        val update: ScriptUpdateResult.UpdateAvailable,
        val releaseNotes: String?,
    )

    companion object {
        private const val LOG_TAG = "SbgWebView"
        private const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_APPLIED_GAME_THEME = "applied_game_theme"
        private const val KEY_APPLIED_GAME_LANGUAGE = "applied_game_language"
        private const val SKIP_BUTTON_CONNECT_DELAY_MS = 2_000L
        private const val SKIP_BUTTON_DOWNLOAD_DELAY_MS = 5_000L
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RELEASE_NOTES_MAX_HEIGHT_DP = 200
        private const val RELEASE_NOTES_PADDING_DP = 24
    }
}

/**
 * Настройки WebView для снижения вероятности ANR при тяжёлом canvas-рендеринге.
 *
 * WebView встроен в Android layout system — его compositor работает синхронно
 * с Android RenderThread. При тяжёлом canvas (OpenLayers карта) UI thread
 * блокируется на syncFrameState, что может вызывать ANR на слабых устройствах.
 */
internal fun configureWebViewPerformance(
    webView: WebView,
    sdkVersion: Int = Build.VERSION.SDK_INT,
) {
    // Промотировать WebView в hardware layer: контент кэшируется как GPU-текстура,
    // что улучшает compositor scheduling при тяжёлом canvas-рендеринге.
    // Референс: Anmiles (refs/anmiles/) использует ту же настройку.
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

    // Не позволяет системе понижать приоритет renderer-процесса WebView.
    // Без этого под нагрузкой renderer получает меньше CPU time →
    // UI thread блокируется в ожидании кадров → ANR.
    if (sdkVersion >= Build.VERSION_CODES.O) {
        webView.setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            true, // понижать приоритет когда Activity невидима
        )
    }
}
