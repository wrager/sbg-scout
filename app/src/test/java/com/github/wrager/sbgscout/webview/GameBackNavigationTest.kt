package com.github.wrager.sbgscout.webview

import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.WebView
import com.github.wrager.sbgscout.config.GameUrls
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
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
        GameUrls.loginUrlOverride = LOGIN_URL
        every { webView.evaluateJavascript(GameBackNavigation.BACK_PRESS_SCRIPT, any()) } answers {
            secondArg<ValueCallback<String>?>()?.onReceiveValue(pageResult)
        }
        every { webView.goBack() } just Runs
        stubHistory(APP_URL)
    }

    @After
    fun tearDown() {
        GameUrls.loginUrlOverride = null
    }

    @Test
    fun `press handled by the page keeps history and activity`() {
        pageResult = HANDLED
        stubHistory(APP_URL, STORE_URL)

        navigation.onBackPressed()

        verify(exactly = 0) { webView.goBack() }
        assertEquals(0, exits)
    }

    @Test
    fun `unhandled press goes back to the previous game page`() {
        stubHistory(APP_URL, STORE_URL)

        navigation.onBackPressed()

        verify { webView.goBack() }
        assertEquals(0, exits)
    }

    @Test
    fun `unhandled press goes back within the document`() {
        stubHistory(APP_URL, "$APP_URL#_game")

        navigation.onBackPressed()

        verify { webView.goBack() }
        assertEquals(0, exits)
    }

    @Test
    fun `unhandled press does not return to the login page`() {
        stubHistory(LOGIN_URL, APP_URL)

        navigation.onBackPressed()

        verify(exactly = 0) { webView.goBack() }
        assertEquals(1, exits)
    }

    @Test
    fun `login page with slash and query is still the login page`() {
        stubHistory("$LOGIN_URL/?next=app", APP_URL)

        navigation.onBackPressed()

        verify(exactly = 0) { webView.goBack() }
        assertEquals(1, exits)
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

    /** История WebView: последний URL текущий, предыдущие доступны для goBack. */
    private fun stubHistory(vararg urls: String) {
        val history = mockk<WebBackForwardList>()
        every { history.currentIndex } returns urls.lastIndex
        every { history.getItemAtIndex(any()) } answers { historyItem(urls.getOrNull(firstArg())) }
        every { webView.copyBackForwardList() } returns history
        every { webView.canGoBack() } returns (urls.size > 1)
    }

    private fun historyItem(url: String?): WebHistoryItem? {
        if (url == null) return null
        val item = mockk<WebHistoryItem>()
        every { item.url } returns url
        return item
    }

    private companion object {
        const val HANDLED = "\"handled\""
        const val UNHANDLED = "\"unhandled\""
        const val LOGIN_URL = "https://game.test/login"
        const val APP_URL = "https://game.test/app"
        const val STORE_URL = "https://game.test/store/"
    }
}
