package com.github.wrager.sbgscout.webview

import android.webkit.ValueCallback
import android.webkit.WebView
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit-тесты [GameBackNavigation]: что происходит после ответа страницы
 * на нажатие "назад". Сам скрипт страницы проверяет e2e-тест
 * `GameBackNavigationE2ETest`.
 *
 * 🤖 Generated with Claude Fable 5.1
 */
class GameBackNavigationTest {
    private val webView = mockk<WebView>()
    private var exits = 0
    private var pageResult = UNHANDLED
    private val navigation = GameBackNavigation(webView) { exits++ }

    @Before
    fun setUp() {
        every { webView.evaluateJavascript(GameBackNavigation.BACK_PRESS_SCRIPT, any()) } answers {
            secondArg<ValueCallback<String>?>()?.onReceiveValue(pageResult)
        }
        every { webView.canGoBack() } returns false
        every { webView.goBack() } just Runs
    }

    @Test
    fun `press handled by the page keeps history and activity`() {
        pageResult = HANDLED
        every { webView.canGoBack() } returns true

        navigation.onBackPressed()

        verify(exactly = 0) { webView.goBack() }
        assertEquals(0, exits)
    }

    @Test
    fun `unhandled press goes back in history`() {
        every { webView.canGoBack() } returns true

        navigation.onBackPressed()

        verify { webView.goBack() }
        assertEquals(0, exits)
    }

    @Test
    fun `unhandled press without history exits`() {
        navigation.onBackPressed()

        verify(exactly = 0) { webView.goBack() }
        assertEquals(1, exits)
    }

    @Test
    fun `missing page result counts as unhandled`() {
        pageResult = "null"

        navigation.onBackPressed()

        assertEquals(1, exits)
    }

    private companion object {
        const val HANDLED = "\"handled\""
        const val UNHANDLED = "\"unhandled\""
    }
}
