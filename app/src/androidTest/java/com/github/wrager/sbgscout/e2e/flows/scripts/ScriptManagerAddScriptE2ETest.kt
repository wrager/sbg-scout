package com.github.wrager.sbgscout.e2e.flows.scripts

import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.contrib.RecyclerViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.FakeGameServerRule
import com.github.wrager.sbgscout.e2e.infra.RecyclerViewChildAction.clickChildViewWithId
import com.github.wrager.sbgscout.e2e.infra.ScriptStorageFixture
import com.github.wrager.sbgscout.launcher.LauncherActivity
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Добавление пользовательского скрипта через ввод URL в диалоге «Add script».
 *
 * Happy path: URL указывает на валидный .user.js с корректным header →
 * скрипт скачивается через HttpFetcher (переписан на fake-сервер), парсится,
 * сохраняется в storage.
 *
 * Negative path: URL возвращает файл без UserScript header → ScriptInstaller
 * возвращает InvalidHeader → event ScriptAddFailed, скрипт в storage не появляется.
 */
@RunWith(AndroidJUnit4::class)
class ScriptManagerAddScriptE2ETest {

    @get:Rule
    val fakeServer = FakeGameServerRule()

    private val server get() = fakeServer.server
    private var scenario: ActivityScenario<LauncherActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun addScriptViaUrl_validHeader_savesScriptInStorage() {
        // Контент размещён как если бы пользовательский URL был на GitHub, т.к.
        // HttpRewriter маршрутизирует только github.com/api.github.com на fake-сервер.
        // Используем existing dispatcher: /gh-web/<owner>/<repo>/releases/latest/download/<file>.
        val content = AssetLoader.read("fixtures/scripts/custom-v1.0.0.user.js")
        server.stubScriptAsset("user", "repo", "latest", "script.user.js", content)
        val url = "https://github.com/user/repo/releases/latest/download/script.user.js"

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        openAddScriptDialog()

        // Ввод URL и клик "Add"
        onView(withId(R.id.scriptUrlInput)).perform(replaceText(url))
        val addLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.add)
        onView(withText(addLabel)).perform(click())

        val storage = ScriptStorageFixture.storage()
        val saved = waitForScript(storage) { it.header.name == "Custom E2E Script" }
        assertEquals("1.0.0", saved.header.version)
        // Custom script — не помечен как preset.
        org.junit.Assert.assertFalse("Кастомный скрипт не должен быть isPreset", saved.isPreset)
    }

    @Test
    fun addScriptViaUrl_invalidHeader_doesNotSaveScript() {
        val content = AssetLoader.read("fixtures/scripts/no-header.user.js")
        server.stubScriptAsset("user", "repo", "latest", "broken.user.js", content)
        val url = "https://github.com/user/repo/releases/latest/download/broken.user.js"

        scenario = ActivityScenario.launch(LauncherActivity::class.java)

        openAddScriptDialog()
        onView(withId(R.id.scriptUrlInput)).perform(replaceText(url))
        val addLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.add)
        onView(withText(addLabel)).perform(click())

        // Должна пройти пауза достаточная для отработки корутины download+parse,
        // но скрипт так и не должен появиться в storage.
        Thread.sleep(2_000L)

        val storage = ScriptStorageFixture.storage()
        val saved = storage.getAll().find {
            it.sourceUrl?.contains("broken.user.js") == true
        }
        assertNull("Скрипт без header не должен сохраняться", saved)
    }

    private fun openAddScriptDialog() {
        // Кнопка "Add script" находится в item_add_script.xml — ViewHolder внутри
        // adapter'а RecyclerView (в конце списка). Кликаем через child view action.
        onView(withId(R.id.scriptList)).perform(
            scrollTo<RecyclerView.ViewHolder>(
                hasDescendant(withId(R.id.addScriptButton)),
            ),
        )
        onView(withId(R.id.scriptList)).perform(
            androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withId(R.id.addScriptButton)),
                clickChildViewWithId(R.id.addScriptButton),
            ),
        )
    }

    private fun waitForScript(
        storage: ScriptStorage,
        predicate: (com.github.wrager.sbgscout.script.model.UserScript) -> Boolean,
    ): com.github.wrager.sbgscout.script.model.UserScript {
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
