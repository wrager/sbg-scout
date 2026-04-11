package com.github.wrager.sbgscout.e2e.infra

import android.view.View
import androidx.annotation.IdRes
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher

/**
 * Кастомные [ViewAction]-обёртки для кликов по дочерним view внутри ViewHolder'а
 * RecyclerView. Стандартные Espresso matchers не умеют точно адресовать клик
 * по child-id в контексте конкретного item — только по глобальному id через
 * `onView(withId(...))`, что при множественном совпадении бросает `AmbiguousViewMatcherException`.
 *
 * Используется в связке с `RecyclerViewActions.actionOnHolderItem` / `scrollTo`:
 * ```
 * onView(withId(R.id.scriptList))
 *     .perform(actionOnItem<ViewHolder>(
 *         hasDescendant(withText("Script name")),
 *         clickChildViewWithId(R.id.scriptToggle),
 *     ))
 * ```
 */
object RecyclerViewChildAction {

    fun clickChildViewWithId(@IdRes id: Int): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        override fun getDescription(): String = "Click on child view with id $id"
        override fun perform(uiController: UiController, view: View) {
            val target = view.findViewById<View>(id)
                ?: throw AssertionError("Child view with id $id not found in ${view.javaClass.simpleName}")
            target.performClick()
        }
    }
}
