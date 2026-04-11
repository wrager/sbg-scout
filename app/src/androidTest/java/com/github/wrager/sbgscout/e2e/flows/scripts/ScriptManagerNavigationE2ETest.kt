package com.github.wrager.sbgscout.e2e.flows.scripts

import android.app.Activity
import android.app.Instrumentation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.settings.SettingsActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что toolbar в LauncherActivity содержит кнопку «Settings»,
 * клик по которой запускает [SettingsActivity] через Intent.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerNavigationE2ETest {

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun toolbarSettingsMenuItem_launchesSettingsActivity() {
        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val settingsLabel = targetContext.getString(R.string.settings)
            // menu_launcher.xml объявляет action_settings с showAsAction="never",
            // значит пункт спрятан в overflow-меню toolbar.
            openActionBarOverflowOrOptionsMenu(targetContext)
            onView(withText(settingsLabel)).perform(click())

            Intents.intended(hasComponent(SettingsActivity::class.java.name))
        } finally {
            Intents.release()
        }
    }
}
