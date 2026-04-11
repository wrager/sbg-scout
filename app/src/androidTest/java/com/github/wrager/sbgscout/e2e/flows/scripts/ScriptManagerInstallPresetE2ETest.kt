package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.FakeGameServerRule
import com.github.wrager.sbgscout.e2e.infra.RecyclerViewChildAction.clickChildViewWithId
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Установка пресета SBG Vanilla+ через клик по download-иконке.
 *
 * Происходит полный цикл:
 * 1. Клик по download-иконке пресета
 * 2. LauncherViewModel.downloadScript → ScriptDownloader.download → HttpFetcher.fetch
 * 3. URL `https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js`
 *    переписывается через HttpRewriterFixture на localhost:<port>/gh-web/...
 * 4. FakeGameDispatcher возвращает содержимое фикстуры svp-v0.8.0.user.js
 * 5. ScriptInstaller парсит header, создаёт UserScript, сохраняет в storage
 * 6. Из-за enabledByDefault=true у SVP-пресета — enabled становится true
 * 7. DefaultScriptProvisioner.markProvisioned фиксирует id пресета
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerInstallPresetE2ETest {

    @get:Rule
    val fakeServer = FakeGameServerRule()

    private val server get() = fakeServer.server

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun downloadSvpPreset_savesScriptAsPresetAndEnabledByDefault() {
        server.stubScriptAsset(
            owner = "wrager",
            repo = "sbg-vanilla-plus",
            tag = "latest",
            filename = "sbg-vanilla-plus.user.js",
            content = AssetLoader.read("fixtures/scripts/svp-v0.8.0.user.js"),
        )

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        onView(withId(R.id.scriptList)).perform(
            actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText("SBG Vanilla+")),
                clickChildViewWithId(R.id.actionButton),
            ),
        )

        val storage = ScriptStorageFixture.storage()
        val svp = waitForScript(storage) { script ->
            script.header.namespace == "github.com/wrager/sbg-vanilla-plus" &&
                script.header.name == "SBG Vanilla+"
        }

        assertEquals("Версия должна соответствовать фикстуре svp-v0.8.0", "0.8.0", svp.header.version)
        assertTrue("SVP-пресет должен быть enabled=true по умолчанию", svp.enabled)
        assertTrue("Сохранённый скрипт должен быть помечен isPreset=true", svp.isPreset)
        assertEquals(
            "sourceUrl должен совпадать с preset.downloadUrl",
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
            svp.sourceUrl,
        )
    }

    @Test
    fun downloadEuiPreset_savesScriptAsPresetAndDisabledByDefault() {
        server.stubScriptAsset(
            owner = "egorantonov",
            repo = "sbg-enhanced",
            tag = "latest",
            filename = "eui.user.js",
            content = AssetLoader.read("fixtures/scripts/eui-v8.2.0.user.js"),
        )

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        onView(withId(R.id.scriptList)).perform(
            actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText("SBG Enhanced UI")),
                clickChildViewWithId(R.id.actionButton),
            ),
        )

        val storage = ScriptStorageFixture.storage()
        val eui = waitForScript(storage) { script ->
            script.header.namespace == "github.com/egorantonov/sbg-enhanced"
        }

        assertEquals("8.2.0", eui.header.version)
        // EUI не enabledByDefault → после установки должен быть disabled.
        org.junit.Assert.assertFalse(
            "EUI без enabledByDefault должен быть disabled после скачивания",
            eui.enabled,
        )
        assertTrue("EUI помечен isPreset=true", eui.isPreset)
    }

    private fun waitForScript(
        storage: ScriptStorage,
        predicate: (UserScript) -> Boolean,
    ): UserScript {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val match = storage.getAll().find(predicate)
            if (match != null) return match
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Скрипт не появился в storage за ${TIMEOUT_MS}ms")
        throw AssertionError("unreachable")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
