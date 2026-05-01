package com.github.wrager.sbgscout.e2e.screens

import android.view.View
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R

/**
 * PageObject для экрана настроек, открытого как overlay в GameActivity
 * (SettingsFragment в `R.id.settingsContainer`).
 *
 * Все взаимодействия с preferences идут через Preference API, а не через
 * Espresso onView на RecyclerView. Причина: PreferenceFragmentCompat использует
 * RecyclerView, который биндит ViewHolder'ы лениво — небольшой экран может
 * не отрендерить preference вне viewport, и `onView(withText(...))` не найдёт
 * его, даже если скроллить программно. `findPreference(key)` работает с
 * полным списком preference-объектов независимо от рендеринга.
 *
 * Для `Preference.performClick()` есть нюанс: на `TwoStatePreference`
 * (SwitchPreferenceCompat) он корректно флипает состояние и триггерит
 * `OnSharedPreferenceChangeListener`. Для обычного `Preference` он триггерит
 * `onPreferenceClickListener` — ровно то, что делает `SettingsFragment`.
 */
class SettingsOverlayScreen(
    private val scenario: ActivityScenario<GameActivity>,
) : Screen {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    override fun assertDisplayed() {
        scenario.onActivity { activity ->
            val container = activity.findViewById<View>(R.id.settingsContainer)
            check(container.visibility == View.VISIBLE) {
                "Settings overlay должен быть VISIBLE"
            }
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
            check(fragment is PreferenceFragmentCompat) {
                "В settingsContainer должен жить PreferenceFragmentCompat"
            }
        }
    }

    /**
     * Кликает preference по его ключу через Preference API.
     * Находит preference в уже inflated PreferenceScreen и вызывает
     * `performClick` — это триггерит onPreferenceClickListener или toggle
     * для SwitchPreferenceCompat.
     */
    fun clickPreferenceByKey(key: String) {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer) as? PreferenceFragmentCompat
                ?: error("SettingsFragment не найден в settingsContainer")
            val preference = fragment.findPreference<Preference>(key)
                ?: error("Preference с key='$key' не найден в PreferenceScreen")
            preference.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    fun assertPreferenceSummaryContains(key: String, substring: String) {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer) as? PreferenceFragmentCompat
                ?: error("SettingsFragment не найден")
            val preference = fragment.findPreference<Preference>(key)
                ?: error("Preference '$key' не найден")
            val summary = preference.summary?.toString().orEmpty()
            check(summary.contains(substring)) {
                "preference '$key' summary='$summary' не содержит '$substring'"
            }
        }
    }

    fun assertCategoryTitles(@StringRes vararg titleRes: Int) {
        val expectedTitles = titleRes.map { targetContext.getString(it) }.toSet()
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer) as? PreferenceFragmentCompat
                ?: error("SettingsFragment не найден")
            val screen = fragment.preferenceScreen
            val actualTitles = mutableSetOf<String>()
            for (i in 0 until screen.preferenceCount) {
                screen.getPreference(i).title?.let { actualTitles += it.toString() }
            }
            val missing = expectedTitles - actualTitles
            check(missing.isEmpty()) {
                "Не найдены категории: $missing (актуальные: $actualTitles)"
            }
        }
    }

    /**
     * Кликает "Manage scripts" через Preference API → fragment transaction →
     * ScriptListFragment.newEmbeddedInstance() в том же контейнере.
     */
    fun openManageScripts(): ScriptManagerScreen {
        clickPreferenceByKey(KEY_MANAGE_SCRIPTS)
        return ScriptManagerScreen(scenario).waitDisplayed()
    }

    companion object {
        const val KEY_FULLSCREEN = "fullscreen_mode"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_LOCK_PORTRAIT_ORIENTATION = "lock_portrait_orientation"
        const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        const val KEY_MANAGE_SCRIPTS = "manage_scripts"
        const val KEY_RELOAD_GAME = "reload_game"
        const val KEY_OPEN_LINKS_IN_APP = "open_links_in_app"
        const val KEY_CHECK_APP_UPDATE = "check_app_update"
        const val KEY_CHECK_SCRIPT_UPDATES = "check_script_updates"
        const val KEY_APP_VERSION = "app_version"
        const val KEY_REPORT_BUG = "report_bug"
    }
}
