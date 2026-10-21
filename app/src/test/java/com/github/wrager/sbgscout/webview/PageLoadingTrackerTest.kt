package com.github.wrager.sbgscout.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoadingTrackerTest {
    private val states = mutableListOf<Boolean>()
    private val tracker = PageLoadingTracker(states::add)

    @Test
    fun `progress starts before document response and finishes on pre-commit cancellation`() {
        tracker.onProgressChanged(10)
        assertEquals(listOf(true), states)
        tracker.onProgressChanged(100)
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `100 percent does not finish a document with pending resources`() {
        tracker.onProgressChanged(10)
        startDocument(LOGIN)
        tracker.onProgressChanged(10)
        tracker.onProgressChanged(100)
        assertTrue(states.all { it })
        tracker.onPageFinished(LOGIN)
        assertFalse(states.last())
    }

    @Test
    fun `old document finish does not hide indicator before next document response`() {
        startDocument(LOGIN)
        tracker.onProgressChanged(100)
        tracker.onProgressChanged(10)
        tracker.onPageFinished(LOGIN)
        assertTrue(states.all { it })
        startDocument(STORE)
        tracker.onPageFinished(STORE)
        assertFalse(states.last())
    }

    @Test
    fun `late finish of previous URL does not end current loading`() {
        tracker.onPageStarted(LOGIN)
        tracker.onPageStarted(STORE)
        tracker.onPageFinished(LOGIN)

        assertEquals(listOf(true, true), states)
        tracker.onPageFinished(STORE)
        assertEquals(listOf(true, true, false), states)
    }

    @Test
    fun `aborted navigation finishes before new URL commits`() {
        tracker.onPageStarted(STORE)
        tracker.onPageFinished(STORE)

        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `redirect committed after onPageStarted finishes for committed URL`() {
        tracker.onPageStarted(APP)
        tracker.onProgressChanged(50)
        tracker.onVisitedHistoryUpdated(LOGIN)
        tracker.onProgressChanged(100)
        tracker.onPageFinished(LOGIN)

        assertEquals(listOf(true, true, false), states)
    }

    @Test
    fun `pushState during loading moves finish to the new document URL`() {
        assertSameDocumentNavigationDuringLoading(startUrl = APP, newUrl = "$APP#_game")
    }

    @Test
    fun `hash change during loading keeps indicator until document finishes`() {
        assertSameDocumentNavigationDuringLoading(startUrl = APP, newUrl = "$APP#point")
    }

    @Test
    fun `hash removal during loading keeps indicator until document finishes`() {
        assertSameDocumentNavigationDuringLoading(startUrl = "$APP#svp-settings", newUrl = APP)
    }

    @Test
    fun `replaceState with the same URL during loading keeps indicator until document finishes`() {
        assertSameDocumentNavigationDuringLoading(startUrl = APP, newUrl = APP)
    }

    @Test
    fun `query change during loading finishes with the next onPageFinished`() {
        startDocument("$APP?point=abc")
        tracker.onProgressChanged(50)
        tracker.onVisitedHistoryUpdated(APP)
        tracker.onPageFinished(APP)

        assertFalse(states.last())
    }

    @Test
    fun `same-document navigation after loading does not change indicator`() {
        startDocument(APP)
        tracker.onProgressChanged(100)
        tracker.onPageFinished(APP)
        val finishedStates = states.toList()

        tracker.onVisitedHistoryUpdated("$APP#point")
        tracker.onPageFinished("$APP#point")

        assertEquals(finishedStates, states)
    }

    private fun startDocument(url: String) {
        tracker.onPageStarted(url)
        tracker.onVisitedHistoryUpdated(url)
    }

    private fun assertSameDocumentNavigationDuringLoading(
        startUrl: String,
        newUrl: String,
    ) {
        startDocument(startUrl)
        tracker.onProgressChanged(50)
        tracker.onVisitedHistoryUpdated(newUrl)
        tracker.onPageFinished(newUrl)
        assertTrue(states.all { it })

        tracker.onProgressChanged(100)
        tracker.onPageFinished(newUrl)
        assertFalse(states.last())
    }

    private companion object {
        const val APP = "http://test.host/app"
        const val LOGIN = "http://test.host/login"
        const val STORE = "http://test.host/store/"
    }
}
