package com.github.wrager.sbgscout.e2e.screens

import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.R

/**
 * PageObject для экрана настроек, открытого как overlay внутри GameActivity
 * (fragment SettingsFragment в `R.id.settingsContainer`).
 *
 * Все клики идут через Espresso по заголовку preference (`withText(...)`).
 * Перед каждым кликом программно скроллим RecyclerView к нужной позиции,
 * чтобы не зависеть от [androidx.test.espresso.contrib.RecyclerViewActions.scrollTo],
 * который требует отключённых анимаций эмулятора.
 */
class SettingsOverlayScreen(
    private val scenario: ActivityScenario<GameActivity>,
) : Screen {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    override fun assertDisplayed() {
        scenario.onActivity { activity ->
            val container = activity.findViewById<View>(R.id.settingsContainer)
            check(container.visibility == View.VISIBLE) {
                "Settings overlay должен быть VISIBLE"
            }
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.settingsContainer)
            check(fragment is PreferenceFragmentCompat) {
                "В settingsContainer должен жить PreferenceFragmentCompat"
            }
        }
    }

    fun clickPreferenceByTitle(@androidx.annotation.StringRes titleRes: Int) {
        val title = targetContext.getString(titleRes)
        scrollRecyclerToChildWithText(title)
        onView(withText(title)).perform(click())
    }

    /**
     * Кликает "Manage scripts" в preference, возвращает [ScriptManagerScreen]
     * для дальнейших операций. В GameActivity контексте SettingsFragment делает
     * fragment transaction на ScriptListFragment.newEmbeddedInstance().
     */
    fun openManageScripts(): ScriptManagerScreen {
        clickPreferenceByTitle(R.string.settings_manage_scripts)
        return ScriptManagerScreen(scenario).waitDisplayed()
    }

    fun assertPreferenceSummaryContains(@androidx.annotation.StringRes titleRes: Int, summary: String) {
        val title = targetContext.getString(titleRes)
        scrollRecyclerToChildWithText(title)
        scenario.onActivity { activity ->
            val recyclerView = findPreferenceRecyclerView(activity)
                ?: error("preference RecyclerView не найден")
            val itemView = findItemViewWithText(recyclerView, title)
                ?: error("Не найден preference с title='$title'")
            val summaryView = itemView.findViewById<android.widget.TextView>(android.R.id.summary)
                ?: error("Нет android.R.id.summary в preference '$title'")
            check(summaryView.text.toString().contains(summary)) {
                "preference '$title' summary='${summaryView.text}' не содержит '$summary'"
            }
        }
    }

    fun assertCategoriesVisible(vararg titleRes: Int) {
        for (res in titleRes) {
            val title = targetContext.getString(res)
            scrollRecyclerToChildWithText(title)
            scenario.onActivity { activity ->
                val recyclerView = findPreferenceRecyclerView(activity)
                    ?: error("preference RecyclerView не найден")
                val item = findItemViewWithText(recyclerView, title)
                check(item != null) { "Категория '$title' не найдена в preference RecyclerView" }
            }
        }
    }

    private fun scrollRecyclerToChildWithText(text: String) {
        scenario.onActivity { activity ->
            val recyclerView = findPreferenceRecyclerView(activity) ?: return@onActivity
            val adapter = recyclerView.adapter ?: return@onActivity
            for (i in 0 until adapter.itemCount) {
                val holder = recyclerView.findViewHolderForAdapterPosition(i)
                    ?: run {
                        recyclerView.scrollToPosition(i)
                        return@onActivity
                    }
                if (findTextViewWithText(holder.itemView, text) != null) {
                    recyclerView.scrollToPosition(i)
                    return@onActivity
                }
            }
            recyclerView.scrollToPosition(adapter.itemCount - 1)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun findPreferenceRecyclerView(activity: GameActivity): RecyclerView? {
        val container = activity.findViewById<View>(R.id.settingsContainer)
        return findRecyclerViewRecursive(container)
    }

    private fun findRecyclerViewRecursive(view: View?): RecyclerView? {
        if (view == null) return null
        if (view is RecyclerView) return view
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
}
