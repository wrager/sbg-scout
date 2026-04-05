package com.github.wrager.sbgscout.game

import org.junit.Assert.assertEquals
import org.junit.Test

class PullTabPositionTest {

    @Test
    fun `normal mode maps 0 to lower boundary`() {
        assertEquals(5f, PullTabPosition.map(0, fullscreen = false), TOLERANCE)
    }

    @Test
    fun `normal mode maps 100 to upper boundary`() {
        assertEquals(92f, PullTabPosition.map(100, fullscreen = false), TOLERANCE)
    }

    @Test
    fun `fullscreen mode maps 0 to lower boundary`() {
        assertEquals(3f, PullTabPosition.map(0, fullscreen = true), TOLERANCE)
    }

    @Test
    fun `fullscreen mode maps 100 to upper boundary`() {
        assertEquals(97f, PullTabPosition.map(100, fullscreen = true), TOLERANCE)
    }

    @Test
    fun `normal mode midpoint`() {
        // 5 + 0.5 * (92 - 5) = 48.5
        assertEquals(48.5f, PullTabPosition.map(50, fullscreen = false), TOLERANCE)
    }

    @Test
    fun `fullscreen mode midpoint`() {
        // 3 + 0.5 * (97 - 3) = 50
        assertEquals(50f, PullTabPosition.map(50, fullscreen = true), TOLERANCE)
    }

    @Test
    fun `fullscreen range is wider than normal`() {
        val normalRange = PullTabPosition.MAX_VISIBLE_NORMAL - PullTabPosition.MIN_VISIBLE_NORMAL
        val fullscreenRange =
            PullTabPosition.MAX_VISIBLE_FULLSCREEN - PullTabPosition.MIN_VISIBLE_FULLSCREEN
        assertEquals(87f, normalRange, TOLERANCE)
        assertEquals(94f, fullscreenRange, TOLERANCE)
    }

    @Test
    fun `maps linearly across range`() {
        // В normal режиме шаг = 87/100 = 0.87
        val at25 = PullTabPosition.map(25, fullscreen = false)
        val at75 = PullTabPosition.map(75, fullscreen = false)
        assertEquals(5f + 25 * 0.87f, at25, TOLERANCE)
        assertEquals(5f + 75 * 0.87f, at75, TOLERANCE)
    }

    companion object {
        private const val TOLERANCE = 0.01f
    }
}
