package com.github.wrager.sbgscout.e2e.screens

import androidx.test.core.app.ActivityScenario
import com.github.wrager.sbgscout.GameActivity

/**
 * PageObject для fake-страницы логина.
 *
 * Fake-login — это HTML, который отдаёт FakeGameDispatcher. В тестах
 * мы не кликаем по DOM-кнопкам (это canvas-форма в реальной игре), а дёргаем
 * JS-функцию, которая делает POST на `/login/callback`.
 */
class LoginScreen(
    private val scenario: ActivityScenario<GameActivity>,
) : Screen {

    override fun assertDisplayed() = Unit

    fun submitFakeAuth() {
        scenario.onActivity { activity ->
            activity.webView.evaluateJavascript("submitTelegramStub()") { }
        }
    }
}
