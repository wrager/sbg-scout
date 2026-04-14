package com.github.wrager.sbgscout.e2e.infra

import android.view.View
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Программный scroll RecyclerView минуя Espresso-contrib
 * [androidx.test.espresso.contrib.RecyclerViewActions].
 *
 * Стандартный `scrollTo<ViewHolder>(matcher)` при старте проверяет
 * `Settings.Global.WINDOW_ANIMATION_SCALE` и валится с
 * `PerformException: Animations or transitions are enabled on the target device`,
 * если пользовательский эмулятор не имеет отключённых анимаций. На CI это
 * решается `disable-animations: true` в emulator-runner, но локально
 * разработчики не обязаны отключать анимации в Developer options.
 *
 * Программный scroll через `RecyclerView.scrollToPosition` не имеет этой
 * проверки и работает в обоих окружениях. Вызывающий тест должен после
 * scroll дать RecyclerView один layout pass — мы делаем это через
 * `waitForIdleSync` (блокирует главный поток до конца всех отложенных
 * измерений/layout), этого достаточно для любого разумного теста.
 */
object RecyclerViewScrolling {

    fun <A : android.app.Activity> scrollToLastPosition(
        scenario: ActivityScenario<A>,
        @IdRes recyclerViewId: Int,
    ) {
        scenario.onActivity { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(recyclerViewId)
            val adapter = recyclerView.adapter ?: return@onActivity
            val lastPosition = adapter.itemCount - 1
            if (lastPosition >= 0) {
                recyclerView.scrollToPosition(lastPosition)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Ищет ViewHolder, чей itemView содержит потомка с заданным текстом,
     * и возвращает `View` этого ViewHolder'а. Используется для прямого
     * чтения state карточки (например, visibility `conflictContainer`).
     *
     * Возвращает `null`, если нужного ViewHolder не нашлось среди
     * текущих привязанных (это поведение сознательное — тест должен либо
     * поскроллить к нужной позиции заранее, либо считать отсутствие
     * негативным результатом).
     */
    fun <A : android.app.Activity> findItemViewByText(
        scenario: ActivityScenario<A>,
        @IdRes recyclerViewId: Int,
        text: String,
    ): View? {
        var result: View? = null
        scenario.onActivity { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(recyclerViewId)
            for (i in 0 until (recyclerView.adapter?.itemCount ?: 0)) {
                val holder = recyclerView.findViewHolderForAdapterPosition(i) ?: continue
                if (findTextViewWithText(holder.itemView, text) != null) {
                    result = holder.itemView
                    return@onActivity
                }
            }
        }
        return result
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
