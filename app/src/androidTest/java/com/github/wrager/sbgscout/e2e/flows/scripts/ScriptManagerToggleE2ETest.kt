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
import com.github.wrager.sbgscout.e2e.infra.RecyclerViewChildAction.clickChildViewWithId
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что клик по SwitchCompat карточки скрипта пишет новое
 * значение `enabled` в ScriptStorage (через `LauncherViewModel.toggleScript`).
 *
 * Контракт: переключение в UI → немедленное сохранение в SharedPreferences
 * без ожидания перезагрузки экрана. Тест использует programmatic sideload
 * установленного скрипта, чтобы изолировать тест от HTTP-flow установки.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerToggleE2ETest {

    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun toggle_enablesDisabledScript() {
        val script = ScriptStorageFixture.minimalScript(
            name = "Toggle Test",
            enabled = false,
            identifierValue = "toggle-test-enable",
        )
        val storage = ScriptStorageFixture.storage()
        storage.save(script)

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        onView(withId(R.id.scriptList)).perform(
            actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText("Toggle Test")),
                clickChildViewWithId(R.id.scriptToggle),
            ),
        )

        waitUntilEnabled(storage, script.identifier.value, expected = true)
    }

    @Test
    fun toggle_disablesEnabledScript() {
        val script = ScriptStorageFixture.minimalScript(
            name = "Toggle Test",
            enabled = true,
            identifierValue = "toggle-test-disable",
        )
        val storage = ScriptStorageFixture.storage()
        storage.save(script)

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        onView(withId(R.id.scriptList)).perform(
            actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText("Toggle Test")),
                clickChildViewWithId(R.id.scriptToggle),
            ),
        )

        waitUntilEnabled(storage, script.identifier.value, expected = false)
    }

    private fun waitUntilEnabled(
        storage: com.github.wrager.sbgscout.script.storage.ScriptStorage,
        identifierValue: String,
        expected: Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val actual = storage.getAll().find { it.identifier.value == identifierValue }?.enabled
            if (actual == expected) {
                assertTrue("enabled=$expected должен сохраниться в storage", true)
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail(
            "Скрипт $identifierValue не получил enabled=$expected за ${TIMEOUT_MS}ms " +
                "(текущее: ${storage.getAll().find { it.identifier.value == identifierValue }?.enabled})",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
