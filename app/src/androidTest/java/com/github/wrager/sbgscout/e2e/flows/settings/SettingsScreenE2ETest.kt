package com.github.wrager.sbgscout.e2e.flows.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.settings.SettingsActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * e2e-тесты экрана настроек в контексте SettingsActivity (без WebView).
 *
 * Зона ответственности SettingsFragment: владение UI экрана настроек, запись
 * значений в SharedPreferences, навигация на другие экраны через Intent.
 * Применение prefs к окну GameActivity проверяется в отдельных тестах
 * (FullscreenModeE2ETest, KeepScreenOnModeE2ETest).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenE2ETest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val defaultPrefs
        get() = PreferenceManager.getDefaultSharedPreferences(targetContext)

    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before
    fun resetPrefs() {
        defaultPrefs.edit()
            .putBoolean(KEY_FULLSCREEN, false)
            .putBoolean(KEY_KEEP_SCREEN_ON, false)
            .putBoolean(KEY_AUTO_CHECK_UPDATES, true)
            .remove(LauncherActivity.KEY_RELOAD_REQUESTED)
            .commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        defaultPrefs.edit()
            .remove(KEY_FULLSCREEN)
            .remove(KEY_KEEP_SCREEN_ON)
            .remove(KEY_AUTO_CHECK_UPDATES)
            .remove(LauncherActivity.KEY_RELOAD_REQUESTED)
            .commit()
    }

    @Test
    fun settingsActivity_showsAllPreferenceCategories() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)

        val categories = listOf(
            R.string.settings_category_display,
            R.string.settings_category_scripts,
            R.string.settings_category_game,
            R.string.settings_category_updates,
            R.string.settings_category_about,
        )

        for (categoryRes in categories) {
            val title = targetContext.getString(categoryRes)
            scrollToText(title)
            onView(withText(title)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun togglesFullscreenPreference_persistsBothDirections() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.settings_fullscreen)

        assertFalse(
            "Pre-condition: fullscreen должен быть false",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, false),
        )

        scrollToText(title)
        onView(withText(title)).perform(click())

        assertTrue(
            "После первого клика fullscreen_mode должен стать true",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, false),
        )

        onView(withText(title)).perform(click())

        assertFalse(
            "После второго клика fullscreen_mode должен вернуться в false",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, true),
        )
    }

    @Test
    fun togglesKeepScreenOnPreference_persistsBothDirections() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.settings_keep_screen_on)

        scrollToText(title)
        assertFalse(
            "Pre-condition: keep_screen_on должен быть false",
            defaultPrefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        )

        onView(withText(title)).perform(click())

        assertTrue(
            "После первого клика keep_screen_on должен стать true",
            defaultPrefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        )

        onView(withText(title)).perform(click())

        assertFalse(
            "После второго клика keep_screen_on должен вернуться в false",
            defaultPrefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
        )
    }

    @Test
    fun togglesAutoCheckUpdatesPreference_persistsBothDirections() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.settings_auto_check_updates)

        scrollToText(title)
        assertTrue(
            "Pre-condition: auto_check_updates должен быть true",
            defaultPrefs.getBoolean(KEY_AUTO_CHECK_UPDATES, false),
        )

        onView(withText(title)).perform(click())

        assertFalse(
            "После первого клика auto_check_updates должен стать false",
            defaultPrefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
        )

        onView(withText(title)).perform(click())

        assertTrue(
            "После второго клика auto_check_updates должен вернуться в true",
            defaultPrefs.getBoolean(KEY_AUTO_CHECK_UPDATES, false),
        )
    }

    @Test
    fun appVersionPreference_displaysBuildConfigVersionNameInSummary() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.settings_version)

        scrollToText(title)

        onView(withText(BuildConfig.VERSION_NAME)).check(matches(isDisplayed()))
        // Проверяем, что summary строка содержит именно версию из BuildConfig,
        // а не какой-то локализованный static placeholder.
        onView(withText(containsString(BuildConfig.VERSION_NAME))).check(matches(isDisplayed()))
        // И заголовок самого пункта видим.
        onView(withText(title)).check(matches(isDisplayed()))
    }

    @Test
    fun manageScriptsPreference_launchesLauncherActivityWhenNotInGameContext() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.settings_manage_scripts)

        scrollToText(title)

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            onView(withText(title)).perform(click())
            Intents.intended(hasComponent(LauncherActivity::class.java.name))
        } finally {
            Intents.release()
        }
    }

    @Test
    fun checkScriptUpdatesPreference_launchesLauncherActivityWhenNotInGameContext() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.settings_check_script_updates)

        scrollToText(title)

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            onView(withText(title)).perform(click())
            Intents.intended(hasComponent(LauncherActivity::class.java.name))
        } finally {
            Intents.release()
        }
    }

    @Test
    fun reloadGamePreference_setsRequestedFlagAndLaunchesGameActivity() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val title = targetContext.getString(R.string.reload_game)

        scrollToText(title)

        assertFalse(
            "Pre-condition: reload_requested не установлен",
            defaultPrefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false),
        )

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            onView(withText(title)).perform(click())

            assertTrue(
                "Клик должен установить KEY_RELOAD_REQUESTED=true в prefs",
                defaultPrefs.getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false),
            )
            // И сам запуск GameActivity — GameActivity.applySettingsAfterClose
            // или onWindowFocusChanged потом прочитает флаг и перезагрузит WebView.
            Intents.intended(hasComponent(GameActivity::class.java.name))
        } finally {
            Intents.release()
        }
    }

    private fun scrollToText(text: String) {
        onView(withId(androidx.preference.R.id.recycler_view))
            .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(text))))
    }

    private companion object {
        const val KEY_FULLSCREEN = "fullscreen_mode"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
    }
}
