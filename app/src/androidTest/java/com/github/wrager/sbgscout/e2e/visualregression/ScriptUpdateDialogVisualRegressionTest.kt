package com.github.wrager.sbgscout.e2e.visualregression

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.ScriptManagerScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import com.github.wrager.sbgscout.e2e.screenshots.ReadmeScreenshotCapture
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает dialog-script-update-light.png и dialog-script-update-dark.png -
 * диалог release notes после auto-check обновления скриптов.
 *
 * Flow повторяет ManualCheckScriptUpdatesE2ETest: sideload SVP v0.8.0 + stub
 * GitHub-релиза SVP v0.8.1 + клик check_script_updates triggers auto-check
 * в ScriptListFragment.onViewCreated, фрагмент отправляет
 * LauncherEvent.CheckCompleted, диалог script_updates_title показывает
 * release-notes summary.
 *
 * Stub фиксированный (одна версия 0.8.1), capture делается на весь экран
 * (включая dim-overlay). Native-разрешение, без масштабирования.
 */
@RunWith(AndroidJUnit4::class)
class ScriptUpdateDialogVisualRegressionTest : E2ETestBase() {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUpVisualRegression() {
        ReadmeScreenshotCapture.forceRussianLocale()
        StatusBarDemoMode.enable()
    }

    @After
    fun tearDownVisualRegression() {
        StatusBarDemoMode.disable()
        ThemeOverride.resetTheme()
        ReadmeScreenshotCapture.resetLocale()
    }

    @Test
    @VisualRegressionTest
    fun captureScriptUpdateDialogLight() {
        ThemeOverride.forceLightTheme()
        captureScriptUpdateDialog("dialog-script-update-light")
    }

    @Test
    @VisualRegressionTest
    fun captureScriptUpdateDialogDark() {
        ThemeOverride.forceDarkTheme()
        captureScriptUpdateDialog("dialog-script-update-dark")
    }

    private fun captureScriptUpdateDialog(baselineName: String) {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp(SVP_INSTALLED_VERSION)
        server.stubGithubReleasesList(
            "wrager",
            "sbg-vanilla-plus",
            GithubReleaseFixtures.scriptReleasesJson(
                owner = "wrager",
                repo = "sbg-vanilla-plus",
                versions = listOf(SVP_LATEST_VERSION),
                assetName = "sbg-vanilla-plus.user.js",
            ),
        )

        val scenario = launchGameActivity()
        GameScreen(scenario, idling).waitForLoaded()
            .openSettings()
            .clickPreferenceByKey(SettingsOverlayScreen.KEY_CHECK_SCRIPT_UPDATES)
        ScriptManagerScreen(scenario).waitDisplayed()

        val dialogTitle = targetContext.getString(R.string.script_updates_title)
        waitForDialogText(dialogTitle)
        Thread.sleep(DIALOG_SETTLE_MS)

        VisualRegressionCapture.captureFullNative(baselineName)
    }

    private fun sideloadSvp(version: String) {
        ScriptStorageFixture.storage().save(
            UserScript(
                identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
                header = ScriptHeader(
                    name = "SBG Vanilla+",
                    version = version,
                    namespace = "github.com/wrager/sbg-vanilla-plus",
                    match = listOf("https://sbg-game.ru/app/*"),
                ),
                sourceUrl = PresetScripts.SVP.downloadUrl,
                updateUrl = PresetScripts.SVP.updateUrl,
                content = "// sideloaded SVP $version",
                enabled = true,
                isPreset = true,
            ),
        )
    }

    private fun waitForDialogText(text: String) {
        val deadline = SystemClock.uptimeMillis() + DIALOG_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()))
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        throw AssertionError("Диалог с текстом '$text' не появился за ${DIALOG_WAIT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val SVP_INSTALLED_VERSION = "0.8.0"
        const val SVP_LATEST_VERSION = "0.8.1"
        const val DIALOG_WAIT_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
        const val DIALOG_SETTLE_MS = 500L
    }
}
