package com.github.wrager.sbgscout.e2e.flows.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.settings.SettingsActivity
import org.hamcrest.core.CombinableMatcher.both
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Клик по «Сообщить об ошибке» в настройках (в контексте SettingsActivity)
 * должен:
 * 1. Скопировать диагностический отчёт в системный буфер обмена.
 * 2. Запустить `Intent.ACTION_VIEW` на `https://github.com/wrager/sbg-scout/issues/new`
 *    с query-параметрами, описывающими версию, устройство и список скриптов.
 *
 * Содержимое отчёта проверяется unit-тестами [BugReportCollectorTest]; здесь
 * мы фиксируем только сам факт клик → clipboard + Intent на корректный URL,
 * т.к. это e2e-контракт клик-обработчика [SettingsFragment.reportBug].
 */
@RunWith(AndroidJUnit4::class)
class ReportBugE2ETest {

    private var scenario: ActivityScenario<SettingsActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun reportBugPreference_copiesDiagnosticsAndLaunchesGithubIssueIntent() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val title = targetContext.getString(R.string.settings_report_bug)

        onView(withId(androidx.preference.R.id.recycler_view))
            .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(title))))

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            onView(withText(title)).perform(click())

            // 1. Intent на github.com/wrager/sbg-scout/issues/new — с query-параметрами.
            Intents.intended(
                both(hasAction(Intent.ACTION_VIEW))
                    .and(hasDataString(startsWith(ISSUE_URL_PREFIX)))
                    .and(hasDataString(containsString("apk-version="))),
            )

            // 2. Clipboard — должна быть скопирована диагностика (полный текст
            // проверяется BugReportCollectorTest, здесь достаточно убедиться, что
            // в буфере что-то осмысленное есть и содержит имя приложения SBG Scout).
            val clipboardText = readClipboardOnMainSync(targetContext)
            assertNotNull("Clipboard должен содержать отчёт", clipboardText)
            assertTrue(
                "Clipboard должен упоминать SBG Scout: $clipboardText",
                clipboardText!!.contains("SBG Scout", ignoreCase = true),
            )
        } finally {
            Intents.release()
        }
    }

    private fun readClipboardOnMainSync(context: Context): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = manager.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    private companion object {
        const val ISSUE_URL_PREFIX = "https://github.com/wrager/sbg-scout/issues/new"
    }
}
