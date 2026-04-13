package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.launcher.ScriptListFragment
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Полный flow auto-update скриптов от автопроверки до установки обновлений.
 *
 * `newEmbeddedAutoUpdateInstance` в проде создаётся только из
 * `GameActivity.openScriptManagerWithAutoUpdate`, которая вызывается после
 * клика «Update» в `showScriptUpdatesDialog` (диалог из
 * `scheduleAutoUpdateCheck`). То есть путь до auto-update фрагмента такой:
 *
 * 1. prefs auto_check_updates=true, last_update_check=0 → при старте
 *    `scheduleAutoUpdateCheck` параллельно проверяет app и скрипты.
 * 2. App update → UpToDate (стабим релиз с той же версией, что BuildConfig.VERSION_NAME).
 * 3. Script check находит обновление SVP v0.8.0 → v0.8.1 →
 *    `showScriptUpdatesDialog` с title `script_updates_available` и кнопкой
 *    `update`.
 * 4. Клик «Update» → `PendingScriptUpdateStorage.save(details)` +
 *    `openScriptManagerWithAutoUpdate` → overlay открывается,
 *    `SettingsFragment` заменяется на `ScriptListFragment.newEmbeddedAutoUpdateInstance()`.
 * 5. Фрагмент в `onViewCreated` видит `ARG_AUTO_UPDATE = true` и запускает
 *    `viewModel.checkAndUpdateAll` — checker + downloader для каждого
 *    UpdateAvailable.
 * 6. Ждём, пока storage получит SVP v0.8.1.
 *
 * Этот тест сознательно не наследует `disableAutoUpdateCheck` из `E2ETestBase`,
 * так же как [com.github.wrager.sbgscout.e2e.flows.settings.AppUpdateCheckE2ETest].
 */
class AutoUpdateFromDialogE2ETest : E2ETestBase() {

    @Test
    fun scriptUpdatesDialog_updateClick_opensAutoUpdateFragmentAndUpdatesStorage() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)
        sideloadSvp("0.8.0")

        // App update check должен вернуть UpToDate — иначе GameActivity сначала
        // покажет app update dialog, и script updates dialog откроется только
        // после его закрытия (ветка else в scheduleAutoUpdateCheck).
        server.stubGithubReleasesList(
            "wrager",
            "sbg-scout",
            GithubReleaseFixtures.appUpdateReleasesJson(
                version = BuildConfig.VERSION_NAME,
                body = "Current release",
            ),
        )
        // Script update: v0.8.1 доступна по latest.
        val svp081 = AssetLoader.read("fixtures/scripts/svp-v0.8.1.user.js")
        server.stubScriptAsset(
            "wrager", "sbg-vanilla-plus", "latest", "sbg-vanilla-plus.meta.js", svp081,
        )
        server.stubScriptAsset(
            "wrager", "sbg-vanilla-plus", "latest", "sbg-vanilla-plus.user.js", svp081,
        )
        // Releases list для ScriptReleaseNotesProvider и для самого ScriptUpdateChecker
        // (checkScriptUpdates → fetchReleaseNotes).
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

        // Переопределяем disableAutoUpdateCheck — автопроверка нам нужна, т.к.
        // именно она запускает showScriptUpdatesDialog.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        PreferenceManager.getDefaultSharedPreferences(targetContext).edit()
            .putBoolean("auto_check_updates", true)
            .putLong("last_update_check", 0L)
            .commit()

        val scenario = launchGameActivity()
        GameScreen(scenario, idling).waitForLoaded()

        val scriptUpdatesTitle = targetContext.getString(R.string.script_updates_available)
        waitForDialogText(scriptUpdatesTitle)
        onView(withText(scriptUpdatesTitle)).inRoot(isDialog()).check(matches(isDisplayed()))

        val updateLabel = targetContext.getString(R.string.update)
        onView(withText(updateLabel)).inRoot(isDialog()).perform(click())

        // После клика GameActivity.openScriptManagerWithAutoUpdate открывает
        // settings overlay и заменяет SettingsFragment на
        // ScriptListFragment.newEmbeddedAutoUpdateInstance.
        waitUntilEmbeddedScriptListFragmentShown(scenario)

        // Авто-update запускается в onViewCreated через ARG_AUTO_UPDATE;
        // ждём, пока storage SVP обновится до 0.8.1 (подтверждение успешного
        // checkAndUpdateAll без ручного клика Update all).
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

    private fun waitUntilEmbeddedScriptListFragmentShown(
        scenario: androidx.test.core.app.ActivityScenario<com.github.wrager.sbgscout.GameActivity>,
    ) {
        val deadline = SystemClock.uptimeMillis() + FRAGMENT_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var shown = false
            scenario.onActivity { activity ->
                val container = activity.findViewById<View>(R.id.settingsContainer)
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.settingsContainer)
                shown = container.visibility == View.VISIBLE &&
                    fragment is ScriptListFragment &&
                    fragment.isResumed
            }
            if (shown) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("ScriptListFragment не открылся в settingsContainer за ${FRAGMENT_WAIT_TIMEOUT_MS}ms")
    }

    private fun waitForSvpVersion(expected: String) {
        val deadline = SystemClock.uptimeMillis() + STORAGE_WAIT_TIMEOUT_MS
        val storage = ScriptStorageFixture.storage()
        while (SystemClock.uptimeMillis() < deadline) {
            val version = storage.getAll()
                .find { it.header.name == "SBG Vanilla+" }
                ?.header?.version
            if (version == expected) {
                assertTrue(
                    "SVP должен быть enabled после auto-update",
                    storage.getAll().find { it.header.name == "SBG Vanilla+" }?.enabled == true,
                )
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("SVP не достиг версии $expected за ${STORAGE_WAIT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val DIALOG_WAIT_TIMEOUT_MS = 20_000L
        const val FRAGMENT_WAIT_TIMEOUT_MS = 5_000L
        const val STORAGE_WAIT_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 200L
    }
}
