package com.github.wrager.sbgscout.e2e.flows.scripts

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.FakeGameServerRule
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сценарий:
 * 1. Игра в прошлой сессии использовала SVP v0.8.0 (snapshot в InjectionStateStorage).
 * 2. В текущей сессии в storage — SVP v0.8.1 (фактически обновлён через что-то).
 * 3. LauncherViewModel.refreshScriptList видит, что currentEnabledSnapshot отличается
 *    от lastInjectedSnapshot → reloadNeeded=true → UI показывает кнопку "Reload game".
 * 4. Клик по кнопке записывает KEY_RELOAD_REQUESTED=true в prefs и запускает GameActivity
 *    через Intent с FLAG_ACTIVITY_CLEAR_TOP.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerReloadButtonE2ETest {

    @get:Rule
    val fakeServer = FakeGameServerRule()

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun reloadButton_visibleAfterScriptChange_launchesGameActivity() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        // Snapshot предыдущей инжекции: SVP v0.8.0 enabled
        val oldSvp = buildSvpScript("0.8.0", enabled = true)
        val injectionStorage = InjectionStateStorage(
            targetContext.getSharedPreferences("scripts", Context.MODE_PRIVATE),
        )
        injectionStorage.saveSnapshot(listOf(oldSvp))

        // Текущее состояние: SVP v0.8.1 enabled (отличается от snapshot'а по version)
        ScriptStorageFixture.storage().save(buildSvpScript("0.8.1", enabled = true))

        // Сбросить флаг reload перед тестом
        PreferenceManager.getDefaultSharedPreferences(targetContext)
            .edit().remove(LauncherActivity.KEY_RELOAD_REQUESTED).commit()

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        // reloadButton должен быть видим
        onView(withId(R.id.reloadButton)).check(matches(isDisplayed()))

        Intents.init()
        try {
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            onView(withId(R.id.reloadButton)).perform(click())

            Intents.intended(hasComponent(GameActivity::class.java.name))
            assertTrue(
                "После клика reloadButton prefs должны содержать reload_requested=true",
                PreferenceManager.getDefaultSharedPreferences(targetContext)
                    .getBoolean(LauncherActivity.KEY_RELOAD_REQUESTED, false),
            )
        } finally {
            Intents.release()
        }
    }

    private fun buildSvpScript(version: String, enabled: Boolean): UserScript = UserScript(
        identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
        header = ScriptHeader(
            name = "SBG Vanilla+",
            version = version,
            namespace = "github.com/wrager/sbg-vanilla-plus",
            match = listOf("https://sbg-game.ru/app/*"),
        ),
        sourceUrl = PresetScripts.SVP.downloadUrl,
        updateUrl = PresetScripts.SVP.updateUrl,
        content = "// fixture SVP $version",
        enabled = enabled,
        isPreset = true,
    )
}
