package com.github.wrager.sbgscout.e2e.flows.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.hamcrest.core.CombinableMatcher.both
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Клик "Report bug" в settings overlay GameActivity копирует диагностику в
 * ClipboardManager и запускает Intent.ACTION_VIEW на github.com/.../issues/new
 * с query-параметрами (apk-version, android-version, device и т.д.).
 */
class ReportBugE2ETest : E2ETestBase() {

    @Test
    fun reportBugPreference_copiesDiagnosticsAndLaunchesGithubIssueIntent() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        val overlay = game.openSettings()

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_REPORT_BUG)

            // Intent на github.com/wrager/sbg-scout/issues/new — с query.
            Intents.intended(
                both(hasAction(Intent.ACTION_VIEW))
                    .and(hasDataString(startsWith(ISSUE_URL_PREFIX)))
                    .and(hasDataString(containsString("apk-version="))),
            )

            // Clipboard — должна содержать осмысленную диагностику.
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
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
