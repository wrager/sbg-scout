package com.github.wrager.sbgscout

import org.junit.Assert.assertEquals
import org.junit.Test

class TopButtonsStateTest {
    @Test
    fun `game loading shows reload and scout`() {
        assertEquals(
            TopButtonsState(reloadButton = true, scoutButton = true),
            topButtonsState(PageLoadState.GAME_LOADING, scoutButtonReplaced = false, canGoBack = true),
        )
    }

    @Test
    fun `ready game keeps scout while html button is missing`() {
        assertEquals(
            TopButtonsState(reloadButton = false, scoutButton = true),
            topButtonsState(PageLoadState.GAME_READY, scoutButtonReplaced = false, canGoBack = false),
        )
    }

    @Test
    fun `ready game hides scout replaced by html button`() {
        assertEquals(
            TopButtonsState(reloadButton = false, scoutButton = false),
            topButtonsState(PageLoadState.GAME_READY, scoutButtonReplaced = true, canGoBack = false),
        )
    }

    @Test
    fun `login page keeps scout and reload`() {
        val expected = TopButtonsState(reloadButton = true, scoutButton = true)

        assertEquals(expected, topButtonsState(PageLoadState.LOGIN, scoutButtonReplaced = false, canGoBack = true))
        assertEquals(expected, topButtonsState(PageLoadState.LOGIN, scoutButtonReplaced = false, canGoBack = false))
    }

    @Test
    fun `other page with history back to game shows nothing`() {
        assertEquals(
            TopButtonsState(reloadButton = false, scoutButton = false),
            topButtonsState(PageLoadState.OTHER_PAGE, scoutButtonReplaced = false, canGoBack = true),
        )
    }

    @Test
    fun `other page without history keeps reload`() {
        assertEquals(
            TopButtonsState(reloadButton = true, scoutButton = false),
            topButtonsState(PageLoadState.OTHER_PAGE, scoutButtonReplaced = false, canGoBack = false),
        )
    }
}
