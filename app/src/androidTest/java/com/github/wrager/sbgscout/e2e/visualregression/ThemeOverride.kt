package com.github.wrager.sbgscout.e2e.visualregression

import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.SbgScoutApplication
import com.github.wrager.sbgscout.game.GameSettingsReader

/**
 * Принудительно фиксирует тему приложения для VR-теста: записывает
 * `KEY_APPLIED_GAME_THEME` в SharedPreferences и вызывает
 * `AppCompatDelegate.setDefaultNightMode`.
 *
 * Сценарий применения - в `@Before` теста, ДО `launchGameActivity()`:
 *
 * 1. SharedPreferences читает `GameActivity.restoreLastAppliedGameSettings`
 *    в `onCreate` до `setContentView`, поэтому без записи в prefs Activity
 *    оставит тему на дефолтной (auto), и тест станет недетерминированным
 *    в зависимости от системной темы эмулятора.
 * 2. `setDefaultNightMode` обновляет тему текущего процесса на лету (с
 *    `configChanges` в манифесте recreation не происходит). Без этого вызова
 *    `Application.onCreate` уже отработал в начале процесса с пустыми prefs,
 *    и до старта Activity тема осталась AUTO.
 *
 * `clearPackageData=true` стирает prefs перед каждым тестом, поэтому запись
 * нужно повторять в `@Before` каждого VR-теста.
 *
 * `resetTheme()` сбрасывает на AUTO - вызывать в `@After`, чтобы следующие
 * тесты не наследовали зафиксированную VR-тему.
 */
object ThemeOverride {
    fun forceLightTheme() {
        applyTheme(GameSettingsReader.ThemeMode.LIGHT, AppCompatDelegate.MODE_NIGHT_NO)
    }

    fun forceDarkTheme() {
        applyTheme(GameSettingsReader.ThemeMode.DARK, AppCompatDelegate.MODE_NIGHT_YES)
    }

    fun resetTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .remove(SbgScoutApplication.KEY_APPLIED_GAME_THEME)
            .apply()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun applyTheme(
        theme: GameSettingsReader.ThemeMode,
        nightMode: Int,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putString(SbgScoutApplication.KEY_APPLIED_GAME_THEME, theme.name)
            .apply()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}
