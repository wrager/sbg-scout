package com.github.wrager.sbgscout.e2e.screens

import android.view.View
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.launcher.ScriptListFragment

/**
 * PageObject для менеджера скриптов, открытого как embedded-фрагмент
 * (`ScriptListFragment.newEmbeddedInstance()`) внутри GameActivity.settingsContainer.
 *
 * Все взаимодействия с RecyclerView идут через программный scroll и прямой
 * доступ к ViewHolder — это обходит требование отключённых анимаций у
 * [androidx.test.espresso.contrib.RecyclerViewActions] и работает одинаково
 * на локальном эмуляторе и в CI.
 */
class ScriptManagerScreen(
    private val scenario: ActivityScenario<GameActivity>,
) : Screen {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    override fun assertDisplayed() {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
            check(fragment is ScriptListFragment) {
                "В settingsContainer должен быть ScriptListFragment, а сейчас ${fragment?.javaClass?.simpleName}"
            }
        }
    }

    fun waitDisplayed(timeoutMs: Long = WAIT_TIMEOUT_MS): ScriptManagerScreen {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.settingsContainer)
                ready = fragment is ScriptListFragment && fragment.isResumed
            }
            if (ready) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return this
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("ScriptListFragment не стал RESUMED за ${timeoutMs}ms")
    }

    /** Кликает на дочернюю view с указанным id внутри карточки скрипта. */
    fun clickCardChildView(cardName: String, @IdRes childId: Int) {
        scenario.onActivity { activity ->
            val recyclerView = findScriptRecyclerView(activity)
                ?: error("ScriptListFragment RecyclerView не найден")
            scrollToCard(recyclerView, cardName)
            val itemView = findItemViewWithText(recyclerView, cardName)
                ?: error("Карточка '$cardName' не найдена в RecyclerView")
            val child = itemView.findViewById<View>(childId)
                ?: error("Child view id=$childId не найден в карточке '$cardName'")
            child.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Возвращает View-объект карточки скрипта по имени для прямого чтения state. */
    fun cardView(cardName: String): View? {
        var result: View? = null
        scenario.onActivity { activity ->
            val recyclerView = findScriptRecyclerView(activity) ?: return@onActivity
            scrollToCard(recyclerView, cardName)
            result = findItemViewWithText(recyclerView, cardName)
        }
        return result
    }

    fun clickCheckUpdates() {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.checkUpdatesButton).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    fun clickReloadGame() {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.reloadButton).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    fun isReloadButtonVisible(): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            visible = activity.findViewById<View>(R.id.reloadButton).visibility == View.VISIBLE
        }
        return visible
    }

    /** Клик «Delete» в overflow-меню карточки + подтверждение в MaterialAlertDialog. */
    fun deleteCardViaOverflow(cardName: String) {
        clickCardChildView(cardName, R.id.actionButton)
        val deleteTitle = targetContext.getString(R.string.delete_script)
        onView(withText(deleteTitle)).perform(click())
        val confirmLabel = targetContext.getString(R.string.delete)
        onView(withText(confirmLabel)).perform(click())
    }

    /** Добавить скрипт через диалог "Add script" (ввод URL). */
    fun openAddScriptDialogAndSubmitUrl(url: String) {
        // Скроллим до последней позиции (item_add_script всегда в конце).
        scenario.onActivity { activity ->
            val recyclerView = findScriptRecyclerView(activity) ?: return@onActivity
            val adapter = recyclerView.adapter ?: return@onActivity
            if (adapter.itemCount > 0) {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val lastHolder = recyclerView.findViewHolderForAdapterPosition(adapter.itemCount - 1)
            val addButton = lastHolder?.itemView?.findViewById<View>(R.id.addScriptButton)
                ?: error("Add script button not found in RecyclerView footer")
            addButton.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Ввод URL в диалог и клик Add.
        onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.scriptUrlInput))
            .perform(androidx.test.espresso.action.ViewActions.replaceText(url))
        val addLabel = targetContext.getString(R.string.add)
        onView(withText(addLabel)).perform(click())
    }

    private fun scrollToCard(recyclerView: RecyclerView, cardName: String) {
        val adapter = recyclerView.adapter ?: return
        for (i in 0 until adapter.itemCount) {
            val holder = recyclerView.findViewHolderForAdapterPosition(i) ?: continue
            if (findTextViewWithText(holder.itemView, cardName) != null) {
                recyclerView.scrollToPosition(i)
                return
            }
        }
        // ViewHolder не привязан — пройдёмся позициями, чтобы bindViewHolder
        // прокинул данные и можно было найти карточку.
        for (i in 0 until adapter.itemCount) {
            recyclerView.scrollToPosition(i)
        }
    }

    private fun findScriptRecyclerView(activity: GameActivity): RecyclerView? {
        val container = activity.findViewById<View>(R.id.settingsContainer)
        return findRecyclerViewRecursive(container)
    }

    private fun findRecyclerViewRecursive(view: View?): RecyclerView? {
        if (view == null) return null
        // Берём только нашу id — preference RecyclerView в SettingsFragment
        // имеет другой id (androidx.preference.R.id.recycler_view).
        if (view.id == R.id.scriptList && view is RecyclerView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val match = findRecyclerViewRecursive(view.getChildAt(i))
                if (match != null) return match
            }
        }
        return null
    }

    private fun findItemViewWithText(recyclerView: RecyclerView, text: String): View? {
        for (i in 0 until (recyclerView.adapter?.itemCount ?: 0)) {
            val holder = recyclerView.findViewHolderForAdapterPosition(i) ?: continue
            if (findTextViewWithText(holder.itemView, text) != null) return holder.itemView
        }
        return null
    }

    private fun findTextViewWithText(view: View, text: String): View? {
        if (view is android.widget.TextView && view.text?.toString() == text) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val match = findTextViewWithText(view.getChildAt(i), text)
                if (match != null) return match
            }
        }
        return null
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
