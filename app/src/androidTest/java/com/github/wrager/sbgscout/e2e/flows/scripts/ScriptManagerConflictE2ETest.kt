package com.github.wrager.sbgscout.e2e.flows.scripts

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.FakeGameServerRule
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет работу [com.github.wrager.sbgscout.script.preset.StaticConflictRules]
 * через e2e:
 *
 * - SVP v0.8.0 + EUI v8.1.0 (обе enabled) → StaticConflictRules говорит, что
 *   SVP несовместим с EUI версии < 8.2.0 → карточка SVP показывает блок
 *   "Incompatible with: SBG Enhanced UI", карточка EUI симметрично показывает
 *   "Incompatible with: SBG Vanilla+".
 * - SVP v0.8.0 + EUI v8.2.0 → compatibleSince constraint разрешает конфликт,
 *   блок conflictContainer не виден ни у одной из карточек.
 *
 * Sideload через ScriptStorageFixture: используем preset downloadUrl и
 * `isPreset=true`, чтобы LauncherViewModel.resolvePresetIdentifier связал
 * стор-скрипты с PresetScripts.SVP/EUI, и ConflictDetector смог применить
 * правила, которые работают на уровне preset identifier.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerConflictE2ETest {

    @get:Rule
    val fakeServer = FakeGameServerRule()

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun svpPlusOldEui_showsConflictOnBothCards() {
        sideloadSvp(version = "0.8.0", enabled = true)
        sideloadEui(version = "8.1.0", enabled = true)

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        // conflictLabel — TextView с static string "Incompatible with:" в item_script.xml.
        // Рендерится только внутри карточек, у которых conflictContainer VISIBLE.
        scrollTo("SBG Vanilla+")
        onView(
            withText(R.string.conflict_label),
        ).check(matches(isDisplayed()))

        // В строке conflictNames на карточке SVP должно быть имя EUI-скрипта.
        scrollTo("SBG Enhanced UI")
        onView(withText("SBG Enhanced UI")).check(matches(isDisplayed()))
    }

    @Test
    fun svpPlusNewEui_hasNoConflict() {
        sideloadSvp(version = "0.8.0", enabled = true)
        sideloadEui(version = "8.2.0", enabled = true)

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        // Ни одной строки "Incompatible with:" быть не должно — StaticConflictRules
        // resolves SVP↔EUI конфликт при EUI >= 8.2.0.
        onView(withText(R.string.conflict_label)).check(doesNotExist())
    }

    private fun sideloadSvp(version: String, enabled: Boolean) {
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
            content = "// fixture SVP v$version",
            enabled = enabled,
            isPreset = true,
        )
        ScriptStorageFixture.storage().save(script)
    }

    private fun sideloadEui(version: String, enabled: Boolean) {
        val script = UserScript(
            identifier = ScriptIdentifier("github.com/egorantonov/sbg-enhanced/SBG Enhanced UI"),
            header = ScriptHeader(
                name = "SBG Enhanced UI",
                version = version,
                namespace = "github.com/egorantonov/sbg-enhanced",
                match = listOf("https://sbg-game.ru/app/*"),
            ),
            sourceUrl = PresetScripts.EUI.downloadUrl,
            updateUrl = PresetScripts.EUI.updateUrl,
            content = "// fixture EUI v$version",
            enabled = enabled,
            isPreset = true,
        )
        ScriptStorageFixture.storage().save(script)
    }

    private fun scrollTo(text: String) {
        onView(withId(R.id.scriptList))
            .perform(scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(text))))
    }
}
