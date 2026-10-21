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
        tracker.onPageStarted(LOGIN)
        tracker.onProgressChanged(10)
        tracker.onProgressChanged(100)
        assertTrue(states.all { it })
        tracker.onPageFinished(LOGIN, LOGIN, LOGIN)
        assertFalse(states.last())
    }

    @Test
    fun `old document finish does not hide indicator before next document response`() {
        tracker.onPageStarted(LOGIN)
        tracker.onProgressChanged(100)
        tracker.onProgressChanged(10)
        tracker.onPageFinished(LOGIN, LOGIN, LOGIN)
        assertTrue(states.all { it })
        tracker.onPageStarted(STORE)
        tracker.onPageFinished(STORE, STORE, STORE)
        assertFalse(states.last())
    }

    @Test
    fun `late finish of previous URL does not end current loading`() {
        tracker.onPageStarted(LOGIN)
        tracker.onPageStarted(STORE)
        tracker.onPageFinished(LOGIN, STORE, STORE)

        assertEquals(listOf(true, true), states)
        tracker.onPageFinished(STORE, STORE, STORE)
        assertEquals(listOf(true, true, false), states)
    }

    @Test
    fun `aborted navigation finishes before new URL commits`() {
        tracker.onPageStarted(STORE)
        tracker.onPageFinished(STORE, APP, null)

        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `redirect finishes for original navigation URL`() {
        tracker.onPageStarted(APP)
        tracker.onPageFinished(LOGIN, LOGIN, APP)

        assertEquals(listOf(true, false), states)
    }

    private companion object {
        const val APP = "http://test.host/app"
        const val LOGIN = "http://test.host/login"
        const val STORE = "http://test.host/store/"
    }
}
