package com.github.wrager.sbgscout.e2e.flows.scripts

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что при открытии LauncherActivity список скриптов отражает
 * текущее состояние ScriptStorage:
 *
 * - Без установленных скриптов: видны три пресета (SVP / EUI / CUI)
 *   в состоянии "не загружен".
 * - С programmatically-sideload'нутым скриптом: он присутствует в списке
 *   рядом с пресетами.
 *
 * Sideload идёт напрямую через [ScriptStorageFixture.storage], минуя HTTP, —
 * этого достаточно, чтобы проверить рендеринг существующего state. HTTP-flow
 * установки пресетов покрывается отдельными тестами.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerListE2ETest {

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun emptyStorage_showsThreePresetsInList() {
        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        // Все три пресета видны по displayName — это то, что Adapter рисует
        // как заголовок карточки (см. ScriptListAdapter.kt).
        for (presetName in listOf("SBG Vanilla+", "SBG Enhanced UI", "SBG CUI")) {
            onView(withId(R.id.scriptList))
                .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(presetName))))
            onView(withText(presetName)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun sideloadedScript_showsInScriptList() {
        val script = ScriptStorageFixture.minimalScript("My Custom Script", version = "2.3.4")
        ScriptStorageFixture.storage().save(script)

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        onView(withId(R.id.scriptList))
            .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText("My Custom Script"))))
        onView(withText("My Custom Script")).check(matches(isDisplayed()))
    }
}
