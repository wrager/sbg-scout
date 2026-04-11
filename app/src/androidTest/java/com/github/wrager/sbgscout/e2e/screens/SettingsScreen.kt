package com.github.wrager.sbgscout.e2e.screens

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.github.wrager.sbgscout.R
import androidx.test.platform.app.InstrumentationRegistry

/**
 * PageObject для экрана настроек ([com.github.wrager.sbgscout.settings.SettingsActivity]).
 *
 * Экран — `PreferenceFragmentCompat`, взаимодействие через стандартный
 * Espresso по withText (заголовки preferences берутся из strings.xml
 * target-apk, т.к. в androidTest доступ к ресурсам приложения через
 * targetContext).
 */
class SettingsScreen : Screen {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    override fun assertDisplayed() {
        val title = targetContext.getString(R.string.settings)
        onView(withText(title)).check(matches(isDisplayed()))
    }
}
