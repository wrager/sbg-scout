package com.github.wrager.sbgscout

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.github.wrager.sbgscout.config.GameUrls
import com.github.wrager.sbgscout.game.GameSettingsReader

/**
 * Инициализирует тему и язык до создания любой Activity.
 *
 * Рекомендованный AppCompat паттерн: `setDefaultNightMode` должно вызываться
 * из `Application.onCreate()`. Иначе при холодном старте Activity успевает
 * инфлейтить layout с дефолтной (не night) конфигурацией до того, как
 * GameActivity.onCreate успеет применить сохранённую тему.
 *
 * Источник значений — SharedPreferences, куда GameActivity записывает каждый
 * раз, когда применяет тему/язык из настроек игры.
 */
class SbgScoutApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        applyStoredTheme(prefs.getString(KEY_APPLIED_GAME_THEME, null))
        applyStoredLanguage(prefs.getString(KEY_APPLIED_GAME_LANGUAGE, null))
        GameUrls.betaServerEnabled = prefs.getBoolean(KEY_BETA_SERVER_ENABLED, false)
    }

    companion object {
        const val KEY_APPLIED_GAME_THEME = "applied_game_theme"
        const val KEY_APPLIED_GAME_LANGUAGE = "applied_game_language"
        const val KEY_BETA_SERVER_ENABLED = "beta_server_enabled"

        internal fun applyStoredTheme(themeName: String?) {
            if (themeName == null) return
            val theme = try {
                GameSettingsReader.ThemeMode.valueOf(themeName)
            } catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
                return
            }
            val nightMode = when (theme) {
                GameSettingsReader.ThemeMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                GameSettingsReader.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                GameSettingsReader.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        internal fun applyStoredLanguage(language: String?) {
            if (language == null) return
            val locales = if (language == "sys") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
