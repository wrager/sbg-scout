package com.github.wrager.sbgscout

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeButtonsStateTest {
    @Test
    fun `game loading shows label, reload and scout`() {
        assertEquals(
            NativeButtonsState(loadingLabel = true, reloadButton = true, scoutButton = true),
            nativeButtonsState(PageLoadState.GAME_LOADING, scoutButtonReplaced = false, canGoBack = true),
        )
    }

    @Test
    fun `ready game keeps scout while html button is missing`() {
        assertEquals(
            NativeButtonsState(loadingLabel = false, reloadButton = false, scoutButton = true),
            nativeButtonsState(PageLoadState.GAME_READY, scoutButtonReplaced = false, canGoBack = false),
        )
    }

    @Test
    fun `ready game hides scout replaced by html button`() {
        assertEquals(
            NativeButtonsState(loadingLabel = false, reloadButton = false, scoutButton = false),
            nativeButtonsState(PageLoadState.GAME_READY, scoutButtonReplaced = true, canGoBack = false),
        )
    }

    @Test
    fun `login page keeps scout and reload without label`() {
        val expected = NativeButtonsState(loadingLabel = false, reloadButton = true, scoutButton = true)

        assertEquals(expected, nativeButtonsState(PageLoadState.LOGIN, scoutButtonReplaced = false, canGoBack = true))
        assertEquals(expected, nativeButtonsState(PageLoadState.LOGIN, scoutButtonReplaced = false, canGoBack = false))
    }

    @Test
    fun `other page with history back to game shows nothing`() {
        assertEquals(
            NativeButtonsState(loadingLabel = false, reloadButton = false, scoutButton = false),
            nativeButtonsState(PageLoadState.OTHER_PAGE, scoutButtonReplaced = false, canGoBack = true),
        )
    }

    @Test
    fun `other page without history keeps reload without label`() {
        assertEquals(
            NativeButtonsState(loadingLabel = false, reloadButton = true, scoutButton = false),
            nativeButtonsState(PageLoadState.OTHER_PAGE, scoutButtonReplaced = false, canGoBack = false),
        )
    }
}
