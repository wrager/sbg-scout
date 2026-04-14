package com.github.wrager.sbgscout.e2e.screens

import android.view.View
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
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

    /**
     * Ждёт появления карточки с заданным именем в RecyclerView.
     *
     * LauncherViewModel.loadScripts() запускает coroutine, которая обновляет
     * uiState через Flow.collect. ScriptListAdapter биндит ViewHolder после
     * submitList — это occurs после того, как fragment уже `isResumed`. Без
     * polling'а можно поймать момент, когда `findViewHolderForAdapterPosition`
     * возвращает null для ещё не биндованной позиции.
     */
    fun waitForCard(cardName: String, timeoutMs: Long = CARD_WAIT_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = cardView(cardName) != null
            if (found) return
            Thread.sleep(CARD_POLL_INTERVAL_MS)
        }
        throw AssertionError("Карточка '$cardName' не появилась в RecyclerView за ${timeoutMs}ms")
    }

    /** Кликает на дочернюю view с указанным id внутри карточки скрипта. */
    fun clickCardChildView(cardName: String, @IdRes childId: Int) {
        waitForCard(cardName)
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
            findFragmentView(activity, R.id.checkUpdatesButton)?.performClick()
                ?: error("checkUpdatesButton не найден внутри ScriptListFragment")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    fun clickReloadGame() {
        scenario.onActivity { activity ->
            findFragmentView(activity, R.id.reloadButton)?.performClick()
                ?: error("reloadButton не найден внутри ScriptListFragment")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    fun isReloadButtonVisible(): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            // R.id.reloadButton объявлен сразу в activity_game.xml (кнопка перезагрузки
            // WebView на экране загрузки игры) и в fragment_script_list.xml.
            // activity.findViewById вернёт первую — game reloadButton, который в этот
            // момент скрыт через hideLabelAndReload(). Нам нужна кнопка фрагмента —
            // ищем её внутри fragment view иерархии.
            visible = findFragmentView(activity, R.id.reloadButton)?.visibility == View.VISIBLE
        }
        return visible
    }

    private fun findFragmentView(activity: GameActivity, viewId: Int): View? {
        val fragment = activity.supportFragmentManager
            .findFragmentById(R.id.settingsContainer)
            ?: return null
        return fragment.view?.findViewById(viewId)
    }

    /**
     * Клик «Reinstall» в overflow-меню карточки скрипта. Пункт присутствует
     * только для не-github-hosted скриптов
     * ([com.github.wrager.sbgscout.launcher.ScriptUiItem.isGithubHosted] = false),
     * иначе в меню будет «Select version». Подтверждения нет —
     * [com.github.wrager.sbgscout.launcher.LauncherViewModel.reinstallScript]
     * запускается сразу.
     */
    fun reinstallCardViaOverflow(cardName: String) {
        clickCardChildView(cardName, R.id.actionButton)
        val reinstallLabel = targetContext.getString(R.string.reinstall)
        onView(withText(reinstallLabel)).inRoot(isPlatformPopup()).perform(click())
    }

    /**
     * Клик «Select version» в overflow-меню карточки скрипта. Пункт присутствует
     * только для github-hosted скриптов. После клика
     * [com.github.wrager.sbgscout.launcher.LauncherViewModel.loadVersions] фетчит
     * список релизов и отправляет событие VersionsLoaded, которое
     * [com.github.wrager.sbgscout.launcher.ScriptListFragment.showVersionSelectionDialog]
     * превращает в диалог выбора версии.
     */
    fun selectVersionCardViaOverflow(cardName: String) {
        clickCardChildView(cardName, R.id.actionButton)
        val selectVersionLabel = targetContext.getString(R.string.select_version)
        onView(withText(selectVersionLabel)).inRoot(isPlatformPopup()).perform(click())
    }

    /** Клик «Delete» в overflow-меню карточки + подтверждение в MaterialAlertDialog. */
    fun deleteCardViaOverflow(cardName: String) {
        clickCardChildView(cardName, R.id.actionButton)
        // PopupMenu живёт в отдельном root'е — inRoot(isPlatformPopup()) нужен,
        // чтобы matcher искал именно в нём, не в hierarchy фрагмента. Без этого
        // onView находит MaterialAlertDialog title (тот же текст "Delete script"),
        // который появляется после того, как popup успевает закрыться.
        val deleteTitle = targetContext.getString(R.string.delete_script)
        onView(withText(deleteTitle)).inRoot(isPlatformPopup()).perform(click())
        // После клика по popup item открывается confirm dialog. Кнопка "Delete"
        // в нём живёт в root'е диалога — inRoot(isDialog()).
        val confirmLabel = targetContext.getString(R.string.delete)
        onView(withText(confirmLabel)).inRoot(isDialog()).perform(click())
    }

    /** Добавить скрипт через диалог "Add script" (ввод URL). */
    fun openAddScriptDialogAndSubmitUrl(url: String) {
        // Шаг 1: scroll к последней позиции. scrollToPosition можно звать на main
        // через onActivity, но waitForIdleSync звать изнутри onActivity нельзя —
        // Instrumentation валится с "This method can not be called from the main
        // application thread". Поэтому scroll и click разделены на два блока.
        scenario.onActivity { activity ->
            val recyclerView = findScriptRecyclerView(activity) ?: return@onActivity
            val adapter = recyclerView.adapter ?: return@onActivity
            if (adapter.itemCount > 0) {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Шаг 2: после layout pass ищем ViewHolder и кликаем addScriptButton.
        scenario.onActivity { activity ->
            val recyclerView = findScriptRecyclerView(activity)
                ?: error("scriptList RecyclerView not found")
            val adapter = recyclerView.adapter ?: error("adapter not attached")
            val lastHolder = recyclerView.findViewHolderForAdapterPosition(adapter.itemCount - 1)
            val addButton = lastHolder?.itemView?.findViewById<View>(R.id.addScriptButton)
                ?: error("Add script button not found in RecyclerView footer")
            addButton.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Шаг 3: диалог открыт в отдельном root — ввод URL и клик Add идут
        // через inRoot(isDialog()), иначе Espresso RootViewPicker валится на
        // focused-root timeout (GameActivity в overlay-режиме, дочерний диалог
        // не становится focused root чисто).
        onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.scriptUrlInput))
            .inRoot(isDialog())
            .perform(androidx.test.espresso.action.ViewActions.replaceText(url))
        val addLabel = targetContext.getString(R.string.add)
        onView(withText(addLabel)).inRoot(isDialog()).perform(click())
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
        const val CARD_WAIT_TIMEOUT_MS = 5_000L
        const val CARD_POLL_INTERVAL_MS = 100L
    }
}
