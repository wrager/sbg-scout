package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.FakeGameServerRule
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.After
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Полный цикл check updates → update single script для пресета SVP.
 *
 * 1. SVP v0.8.0 установлен (sideload через preset flags).
 * 2. Stub `.meta.js` возвращает header с версией 0.8.1 → ScriptUpdateChecker
 *    видит UpdateAvailable.
 * 3. Stub `.user.js` возвращает тело v0.8.1 → LauncherViewModel.updateScript
 *    может скачать и установить.
 * 4. Клик «Check for updates» → кнопка становится «Update all».
 * 5. После успешного update — версия в хранилище обновлена до 0.8.1.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerUpdateE2ETest {

    @get:Rule
    val fakeServer = FakeGameServerRule()

    private val server get() = fakeServer.server
    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun checkUpdates_withNewVersion_changesCheckButtonToUpdateAll() {
        sideloadSvp(version = "0.8.0")
        // .meta.js возвращает содержимое svp-v0.8.1 — парсер вытащит @version 0.8.1.
        val svp081 = AssetLoader.read("fixtures/scripts/svp-v0.8.1.user.js")
        server.stubScriptAsset(
            "wrager",
            "sbg-vanilla-plus",
            "latest",
            "sbg-vanilla-plus.meta.js",
            svp081,
        )
        server.stubScriptAsset(
            "wrager",
            "sbg-vanilla-plus",
            "latest",
            "sbg-vanilla-plus.user.js",
            svp081,
        )

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val checkLabel = context.getString(R.string.check_updates)
        val updateAllLabel = context.getString(R.string.update_all)

        onView(withText(checkLabel)).perform(click())

        // После checkUpdates кнопка должна меняться на "Update all".
        // Проверяем через polling — LauncherViewModel.checkUpdates идёт через IO.
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var visible = false
        while (!visible && SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(updateAllLabel)).check(matches(isDisplayed()))
                visible = true
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        if (!visible) {
            fail("Button 'Update all' не появилась за ${TIMEOUT_MS}ms — checkUpdates не завершилось")
        }
    }

    @Test
    fun updateSingleScript_viaUpdateAllButton_installsNewVersionInStorage() {
        sideloadSvp(version = "0.8.0")
        val svp081 = AssetLoader.read("fixtures/scripts/svp-v0.8.1.user.js")
        server.stubScriptAsset(
            "wrager",
            "sbg-vanilla-plus",
            "latest",
            "sbg-vanilla-plus.meta.js",
            svp081,
        )
        server.stubScriptAsset(
            "wrager",
            "sbg-vanilla-plus",
            "latest",
            "sbg-vanilla-plus.user.js",
            svp081,
        )

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val checkLabel = context.getString(R.string.check_updates)
        val updateAllLabel = context.getString(R.string.update_all)

        // check → wait until button becomes "Update all"
        onView(withText(checkLabel)).perform(click())
        waitForButtonText(updateAllLabel)

        // update all
        onView(withText(updateAllLabel)).perform(click())

        // В storage версия должна стать 0.8.1.
        val storage = ScriptStorageFixture.storage()
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val svp = storage.getAll().find {
                it.header.namespace == "github.com/wrager/sbg-vanilla-plus"
            }
            if (svp?.header?.version == "0.8.1") return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail(
            "SVP в storage не обновился до 0.8.1 за ${TIMEOUT_MS}ms: " +
                "${ScriptStorageFixture.storage().getAll()
                    .find { it.header.namespace == "github.com/wrager/sbg-vanilla-plus" }
                    ?.header?.version}",
        )
    }

    private fun waitForButtonText(text: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        fail("Текст '$text' не появился за ${TIMEOUT_MS}ms")
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
        )
        ScriptStorageFixture.storage().save(script)
    }

    // `scrollTo` пока не нужен — кнопки checkUpdates / reload / updateAll в корневом layout, не в RecyclerView.
    @Suppress("UnusedPrivateMember")
    private fun scrollToCard(name: String) {
        onView(withId(R.id.scriptList))
            .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(name))))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
