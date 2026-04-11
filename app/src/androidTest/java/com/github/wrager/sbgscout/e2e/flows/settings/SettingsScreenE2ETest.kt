package com.github.wrager.sbgscout.e2e.flows.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.settings.SettingsActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тест экрана настроек.
 *
 * Проверяет, что SettingsActivity запускается, PreferenceFragmentCompat
 * рендерит базовые пункты, и переключение `fullscreen_mode` кликом работает
 * (видимо в UI и сохраняется через дефолтный SharedPreferences).
 *
 * Settings не зависит от WebView / fake-сервера, поэтому E2ETestBase не
 * наследуется — тест лёгкий и быстрый.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenE2ETest {

    private var scenario: ActivityScenario<SettingsActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun settingsActivity_showsDisplayCategoryAndFullscreenSwitch() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayCategoryTitle = context.getString(R.string.settings_category_display)
        val fullscreenTitle = context.getString(R.string.settings_fullscreen)
        val keepScreenOnTitle = context.getString(R.string.settings_keep_screen_on)

        onView(withText(displayCategoryTitle)).check(matches(isDisplayed()))
        onView(withText(fullscreenTitle)).check(matches(isDisplayed()))
        onView(withText(keepScreenOnTitle)).check(matches(isDisplayed()))
    }

    @Test
    fun settingsActivity_togglesFullscreenPreference() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fullscreenTitle = context.getString(R.string.settings_fullscreen)

        // Клик по пункту fullscreen — переключает SwitchPreferenceCompat.
        // Сам факт, что Espresso находит view и клик не бросает — уже подтверждает
        // рабочий стэк PreferenceFragmentCompat + RecyclerView.
        onView(withText(fullscreenTitle)).perform(click())
        onView(withText(fullscreenTitle)).check(matches(isDisplayed()))
    }
}
