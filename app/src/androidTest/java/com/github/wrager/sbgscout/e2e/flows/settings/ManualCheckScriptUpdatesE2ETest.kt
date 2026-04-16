package com.github.wrager.sbgscout.e2e.flows.settings

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.ScriptManagerScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import org.junit.Test

/**
 * Ручная проверка обновлений скриптов из settings overlay.
 *
 * Клик preference `check_script_updates` в [SettingsOverlayScreen] выполняет
 * fragment transaction с заменой `SettingsFragment` на
 * `ScriptListFragment.newEmbeddedAutoCheckInstance()`. В этом режиме фрагмент
 * в `onViewCreated` сам вызывает `viewModel.checkUpdates()` (через
 * `ARG_AUTO_CHECK = true`), без необходимости нажимать кнопку «Check for updates».
 *
 * Flow теста:
 * 1. Sideload SVP v0.8.0 enabled (как preset).
 * 2. Stub списка релизов GitHub API → SVP v0.8.1. Этот же stub используется
 *    и ScriptUpdateChecker (tag_name), и ScriptReleaseNotesProvider.
 * 3. Открываем overlay и кликаем check_script_updates.
 * 4. Ждём, пока `ScriptListFragment` станет RESUMED.
 * 5. Ждём появления диалога release notes с title `script_updates_title` —
 *    это доказательство, что auto-check отработал и нашёл обновление.
 *
 * Диалог release notes не подтверждается (клик Update all бы запустил реальное
 * обновление, которое уже покрыто в ScriptManagerUpdateE2ETest). Здесь важно
 * показать, что именно `check_script_updates` preference триггерит auto-check
 * без ручного клика на кнопке внутри фрагмента.
 */
class ManualCheckScriptUpdatesE2ETest : E2ETestBase() {

    @Test
    fun checkScriptUpdates_triggersAutoCheckAndShowsReleaseNotesDialog() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0")
        server.stubGithubReleasesList(
            "wrager",
            "sbg-vanilla-plus",
            GithubReleaseFixtures.scriptReleasesJson(
                owner = "wrager",
                repo = "sbg-vanilla-plus",
                versions = listOf("0.8.1"),
                assetName = "sbg-vanilla-plus.user.js",
            ),
        )

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        game.openSettings().clickPreferenceByKey(SettingsOverlayScreen.KEY_CHECK_SCRIPT_UPDATES)

        // Фрагмент открывается транзакцией на том же контейнере — ждём, пока он
        // станет RESUMED (так же, как в openManageScripts).
        val scriptManager = ScriptManagerScreen(scenario).waitDisplayed()
        scriptManager.assertDisplayed()

        // Авто-check запускается в onViewCreated. После завершения coroutine
        // фрагмент отправляет LauncherEvent.CheckCompleted с releaseNotesSummary,
        // ScriptListFragment.handleSpecialEvent показывает диалог script_updates_title.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dialogTitle = context.getString(R.string.script_updates_title)
        waitForDialogText(dialogTitle)
        onView(withText(dialogTitle)).inRoot(isDialog()).check(matches(isDisplayed()))
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
        const val DIALOG_WAIT_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
