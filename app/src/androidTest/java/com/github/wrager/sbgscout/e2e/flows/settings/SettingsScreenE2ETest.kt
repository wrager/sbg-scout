package com.github.wrager.sbgscout.e2e.flows.settings

import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * e2e-тест экрана настроек.
 *
 * Settings не зависит от WebView / fake-сервера, поэтому E2ETestBase не
 * наследуется — тест лёгкий и быстрый. Зона ответственности этого набора —
 * `SettingsFragment` как владелец UI и записи в `SharedPreferences`. Реальное
 * применение fullscreen/keepScreenOn к окну проверяется в отдельных тестах
 * на `GameActivity` (см. FullscreenModeE2ETest), т.к. применение происходит
 * там через `OnSharedPreferenceChangeListener`.
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
        // Начинаем с известного состояния: fullscreen выключен.
        defaultPrefs.edit().putBoolean(KEY_FULLSCREEN, false).commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        defaultPrefs.edit().remove(KEY_FULLSCREEN).commit()
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
    fun settingsActivity_togglesFullscreenPreference_persistsBothDirections() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val fullscreenTitle = targetContext.getString(R.string.settings_fullscreen)

        assertFalse(
            "Pre-condition: fullscreen должен быть выключен",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, false),
        )

        scrollToText(fullscreenTitle)
        onView(withText(fullscreenTitle)).perform(click())

        assertTrue(
            "После первого клика fullscreen_mode должен стать true",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, false),
        )

        onView(withText(fullscreenTitle)).perform(click())

        assertFalse(
            "После второго клика fullscreen_mode должен вернуться в false",
            defaultPrefs.getBoolean(KEY_FULLSCREEN, true),
        )
    }

    private fun scrollToText(text: String) {
        onView(withId(androidx.preference.R.id.recycler_view))
            .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(text))))
    }

    private companion object {
        const val KEY_FULLSCREEN = "fullscreen_mode"
    }
}
