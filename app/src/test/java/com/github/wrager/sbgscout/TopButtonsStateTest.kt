package com.github.wrager.sbgscout

import org.junit.Assert.assertEquals
import org.junit.Test

class TopButtonsStateTest {
    @Test
    fun `game loading shows overlay label reload and scout in the center`() {
        assertEquals(
            TopButtonsState(
                loadingOverlay = true,
                initializingLabel = true,
                reloadButton = true,
                scoutButton = true,
                atTop = false,
            ),
            topButtonsState(PageLoadState.GAME_LOADING, scoutButtonReplaced = false, canGoBack = true),
        )
    }

    @Test
    fun `ready game keeps scout in the center while html button is missing`() {
        assertEquals(
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = false,
                scoutButton = true,
                atTop = false,
            ),
            topButtonsState(PageLoadState.GAME_READY, scoutButtonReplaced = false, canGoBack = false),
        )
    }

    @Test
    fun `ready game hides scout replaced by html button`() {
        val state = topButtonsState(PageLoadState.GAME_READY, scoutButtonReplaced = true, canGoBack = false)

        assertEquals(false, state.scoutButton)
    }

    @Test
    fun `login page keeps scout and reload at the top`() {
        val expected =
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = true,
                scoutButton = true,
                atTop = true,
            )

        assertEquals(expected, topButtonsState(PageLoadState.LOGIN, scoutButtonReplaced = false, canGoBack = true))
        assertEquals(expected, topButtonsState(PageLoadState.LOGIN, scoutButtonReplaced = false, canGoBack = false))
    }

    @Test
    fun `other page with history back to game shows nothing`() {
        assertEquals(
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = false,
                scoutButton = false,
                atTop = true,
            ),
            topButtonsState(PageLoadState.OTHER_PAGE, scoutButtonReplaced = false, canGoBack = true),
        )
    }

    @Test
    fun `other page without history keeps reload at the top`() {
        assertEquals(
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = true,
                scoutButton = false,
                atTop = true,
            ),
            topButtonsState(PageLoadState.OTHER_PAGE, scoutButtonReplaced = false, canGoBack = false),
        )
    }
}
