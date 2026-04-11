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
import com.github.wrager.sbgscout.e2e.infra.RecyclerViewChildAction.clickChildViewWithId
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что пункт «Delete script» в overflow-меню карточки скрипта
 * открывает confirm-диалог, подтверждение которого удаляет скрипт из
 * ScriptStorage (SharedPreferences + файл контента) и из UI.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerDeleteE2ETest {

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun delete_installedScript_removesFromStorage() {
        val script = ScriptStorageFixture.minimalScript(
            name = "Deletable Script",
            identifierValue = "deletable-script",
        )
        val storage = ScriptStorageFixture.storage()
        storage.save(script)

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        // Открыть overflow-меню через actionButton (кастомный ViewAction
        // кликает внутри ViewHolder'а, матчащегося по имени).
        onView(withId(R.id.scriptList)).perform(
            actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText("Deletable Script")),
                clickChildViewWithId(R.id.actionButton),
            ),
        )

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val deleteTitle = targetContext.getString(R.string.delete_script)

        // PopupMenu показывает пункт "Delete script".
        onView(withText(deleteTitle)).perform(click())

        // Confirm dialog показывает "Delete" кнопку (позитивный action).
        val deleteConfirmLabel = targetContext.getString(R.string.delete)
        onView(withText(deleteConfirmLabel)).perform(click())

        waitUntilDeleted(storage, script.identifier.value)
    }

    private fun waitUntilDeleted(
        storage: com.github.wrager.sbgscout.script.storage.ScriptStorage,
        identifierValue: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val script = storage.getAll().find { it.identifier.value == identifierValue }
            if (script == null) {
                assertNull("Script должен исчезнуть из storage", script)
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Script $identifierValue не удалён из storage за ${TIMEOUT_MS}ms")
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
