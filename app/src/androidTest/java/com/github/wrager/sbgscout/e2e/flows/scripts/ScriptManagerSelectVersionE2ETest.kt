package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
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
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.Assert.fail
import org.junit.Test

/**
 * Select version через overflow-меню для github-hosted скрипта.
 *
 * Overflow menu показывает «Select version» (а не «Reinstall»), когда
 * [com.github.wrager.sbgscout.launcher.ScriptUiItem.isGithubHosted] = true —
 * sourceUrl содержит `github.com/<owner>/<repo>`. SVP как preset подходит.
 *
 * Flow:
 * 1. Sideload SVP v0.8.0 с sourceUrl = PresetScripts.SVP.downloadUrl (github URL).
 * 2. Stub `/gh-api/repos/wrager/sbg-vanilla-plus/releases` с ОДНИМ релизом v0.8.1,
 *    чтобы диалог показал одну версию и она была выбрана по умолчанию
 *    (currentIndex = indexOfFirst { it.isCurrent }.coerceAtLeast(0) = 0).
 * 3. Stub asset /gh-web/wrager/sbg-vanilla-plus/releases/download/v0.8.1/sbg-vanilla-plus.user.js.
 * 4. Open manage scripts → overflow → Select version →
 *    LauncherViewModel.loadVersions фетчит releases list →
 *    LauncherEvent.VersionsLoaded → showVersionSelectionDialog.
 * 5. Ждём диалог с title «Select version» и элементом «v0.8.1».
 * 6. Клик «Install» → installVersion(identifier, downloadUrl, isLatest=true, "v0.8.1") →
 *    downloader.download → scriptStorage.save новый скрипт с version=0.8.1.
 * 7. Ждём, пока storage перейдёт на 0.8.1.
 */
class ScriptManagerSelectVersionE2ETest : E2ETestBase() {

    @Test
    fun selectVersion_githubHostedScript_installsSelectedVersion() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0")

        // Список релизов: один тег v0.8.1. По умолчанию в диалоге будет
        // выбран position 0 — именно эту версию мы и хотим установить.
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
        // Asset с версионным тегом — installVersion скачает .user.js.
        val svp081 = AssetLoader.read("fixtures/scripts/svp-v0.8.1.user.js")
        server.stubScriptAsset(
            "wrager", "sbg-vanilla-plus", "v0.8.1", "sbg-vanilla-plus.user.js", svp081,
        )

        val scenario = launchGameActivity()
        val scriptManager = GameScreen(scenario, idling).waitForLoaded()
            .openSettings()
            .openManageScripts()
        scriptManager.waitForCard("SBG Vanilla+")

        scriptManager.selectVersionCardViaOverflow("SBG Vanilla+")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dialogTitle = context.getString(R.string.select_version)
        waitForDialogText(dialogTitle)
        onView(withText(dialogTitle)).inRoot(isDialog()).check(matches(isDisplayed()))
        // В списке одна строка "v0.8.1" — проверяем, что диалог отрендерил
        // именно эту версию как опцию выбора.
        onView(withText("v0.8.1")).inRoot(isDialog()).check(matches(isDisplayed()))

        val installLabel = context.getString(R.string.install)
        onView(withText(installLabel)).inRoot(isDialog()).perform(click())

        waitForSvpVersion("0.8.1")
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
        fail("Диалог с текстом '$text' не появился за ${DIALOG_WAIT_TIMEOUT_MS}ms")
    }

    private fun waitForSvpVersion(expected: String) {
        val deadline = SystemClock.uptimeMillis() + STORAGE_WAIT_TIMEOUT_MS
        val storage = ScriptStorageFixture.storage()
        while (SystemClock.uptimeMillis() < deadline) {
            val version = storage.getAll()
                .find { it.header.name == "SBG Vanilla+" }
                ?.header?.version
            if (version == expected) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("SVP не достиг версии $expected за ${STORAGE_WAIT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val DIALOG_WAIT_TIMEOUT_MS = 10_000L
        const val STORAGE_WAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
