package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.FakeGameServerRule
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.infra.RecyclerViewChildAction.clickChildViewWithId
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Select version» в overflow меню скрипта, установленного с GitHub, показывает
 * диалог выбора из [GithubReleaseProvider], и выбор старой версии устанавливает
 * именно её, с releaseTag = tagName.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerSelectVersionE2ETest {

    @get:Rule
    val fakeServer = FakeGameServerRule()

    private val server get() = fakeServer.server
    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun selectVersionDialog_installsOlderSvpVersion() {
        sideloadSvp(version = "0.8.1")

        server.stubGithubReleasesList(
            "wrager",
            "sbg-vanilla-plus",
            GithubReleaseFixtures.scriptReleasesJson(
                owner = "wrager",
                repo = "sbg-vanilla-plus",
                versions = listOf("0.8.1", "0.8.0"),
                assetName = "sbg-vanilla-plus.user.js",
            ),
        )
        server.stubScriptAsset(
            "wrager",
            "sbg-vanilla-plus",
            "v0.8.0",
            "sbg-vanilla-plus.user.js",
            AssetLoader.read("fixtures/scripts/svp-v0.8.0.user.js"),
        )

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        onView(withId(R.id.scriptList)).perform(
            actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText("SBG Vanilla+")),
                clickChildViewWithId(R.id.actionButton),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        onView(withText(context.getString(R.string.select_version))).perform(click())

        // Диалог показывает список тегов (v0.8.1, v0.8.0). Выбор v0.8.0.
        onView(withText("v0.8.0")).perform(click())
        onView(withText(context.getString(R.string.install))).perform(click())

        // В storage должна оказаться версия 0.8.0 и releaseTag="v0.8.0".
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        val storage = ScriptStorageFixture.storage()
        while (SystemClock.uptimeMillis() < deadline) {
            val svp = storage.getAll().find {
                it.header.namespace == "github.com/wrager/sbg-vanilla-plus"
            }
            if (svp?.header?.version == "0.8.0") {
                assertEquals("v0.8.0", svp.releaseTag)
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("SVP не переустановилась на версию 0.8.0 за ${TIMEOUT_MS}ms")
    }

    private fun sideloadSvp(version: String) {
        val script = UserScript(
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
            releaseTag = "v$version",
        )
        ScriptStorageFixture.storage().save(script)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
