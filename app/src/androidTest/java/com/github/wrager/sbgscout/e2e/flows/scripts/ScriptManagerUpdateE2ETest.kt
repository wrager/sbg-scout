package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.Assert.fail
import org.junit.Test

/**
 * Полный цикл check updates → update single script через embedded-менеджер.
 *
 * Flow:
 * 1. Sideload SVP v0.8.0 enabled (как preset через isPreset=true + preset.downloadUrl).
 * 2. Stub `/gh-api/repos/wrager/sbg-vanilla-plus/releases` с v0.8.1 → ScriptUpdateChecker
 *    видит в `tag_name` более новую версию, возвращает UpdateAvailable.
 *    Этот же stub обслуживает ScriptReleaseNotesProvider.
 * 3. Stub `.user.js` с v0.8.1 — ScriptDownloader дёргает его при реальном
 *    скачивании обновления после клика Update all.
 * 4. Клик "Check for updates" в фрагменте → coroutine → CheckCompleted event
 *    → handleSpecialEvent открывает showUpdateReleaseNotesDialog с positive
 *    button "Update all".
 * 5. Клик "Update all" в диалоге (inRoot(isDialog())) → viewModel.checkAndUpdateAll
 *    → applyUpdate для каждого UpdateAvailable → storage обновляется на v0.8.1.
 */
class ScriptManagerUpdateE2ETest : E2ETestBase() {

    @Test
    fun updateSingleScript_viaUpdateAllDialog_installsNewVersion() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0")
        val svp081 = AssetLoader.read("fixtures/scripts/svp-v0.8.1.user.js")
        server.stubScriptAsset(
            "wrager", "sbg-vanilla-plus", "latest", "sbg-vanilla-plus.user.js", svp081,
        )
        // Releases list: ScriptUpdateChecker сравнивает по tag_name, а
        // ScriptReleaseNotesProvider берёт отсюда же release notes.
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
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings().openManageScripts()
        scriptManager.waitForCard("SBG Vanilla+")

        // Клик "Check for updates" → открывается диалог release notes.
        scriptManager.clickCheckUpdates()

        // Ждём появления positive button "Update all" в диалоге и кликаем по ней.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updateAllLabel = context.getString(R.string.update_all)
        waitForDialogButton(updateAllLabel)
        onView(withText(updateAllLabel)).inRoot(isDialog()).perform(click())

        waitForSvpVersion("0.8.1")
    }

    private fun waitForDialogButton(text: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(
                    androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed(),
                    ),
                )
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        fail("Диалог с кнопкой '$text' не появился за ${TIMEOUT_MS}ms")
    }

    private fun waitForSvpVersion(expected: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        val storage = ScriptStorageFixture.storage()
        while (SystemClock.uptimeMillis() < deadline) {
            val version = storage.getAll()
                .find { it.header.name == "SBG Vanilla+" }
                ?.header?.version
            if (version == expected) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("SVP не достиг версии $expected за ${TIMEOUT_MS}ms")
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
