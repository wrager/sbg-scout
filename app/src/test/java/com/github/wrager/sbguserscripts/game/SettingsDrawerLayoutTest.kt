package com.github.wrager.sbguserscripts.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDrawerLayoutTest {

    /**
     * Тестируем логику определения зоны касания напрямую,
     * без создания реального View (density и tabCenterY задаются вручную).
     */

    @Test
    fun `touch within tab area returns true`() {
        val tabCenter = 500f
        val density = 2f
        val touchAreaHalf = SettingsDrawerLayout.TAB_TOUCH_AREA_HALF_DP * density

        assertTrue(isTouchInTabArea(tabCenter, tabCenter, touchAreaHalf))
        assertTrue(isTouchInTabArea(tabCenter + touchAreaHalf - 1f, tabCenter, touchAreaHalf))
        assertTrue(isTouchInTabArea(tabCenter - touchAreaHalf + 1f, tabCenter, touchAreaHalf))
    }

    @Test
    fun `touch at tab area boundary returns true`() {
        val tabCenter = 500f
        val density = 2f
        val touchAreaHalf = SettingsDrawerLayout.TAB_TOUCH_AREA_HALF_DP * density

        assertTrue(isTouchInTabArea(tabCenter + touchAreaHalf, tabCenter, touchAreaHalf))
        assertTrue(isTouchInTabArea(tabCenter - touchAreaHalf, tabCenter, touchAreaHalf))
    }

    @Test
    fun `touch outside tab area returns false`() {
        val tabCenter = 500f
        val density = 2f
        val touchAreaHalf = SettingsDrawerLayout.TAB_TOUCH_AREA_HALF_DP * density

        assertFalse(isTouchInTabArea(tabCenter + touchAreaHalf + 1f, tabCenter, touchAreaHalf))
        assertFalse(isTouchInTabArea(tabCenter - touchAreaHalf - 1f, tabCenter, touchAreaHalf))
        assertFalse(isTouchInTabArea(0f, tabCenter, touchAreaHalf))
        assertFalse(isTouchInTabArea(1000f, tabCenter, touchAreaHalf))
    }

    @Test
    fun `touch area works with different densities`() {
        val tabCenter = 300f
        val density = 3f
        val touchAreaHalf = SettingsDrawerLayout.TAB_TOUCH_AREA_HALF_DP * density

        // touchAreaHalf = 48 * 3 = 144px → допустимый диапазон: 156..444
        assertTrue(isTouchInTabArea(300f, tabCenter, touchAreaHalf))
        assertTrue(isTouchInTabArea(156f, tabCenter, touchAreaHalf))
        assertTrue(isTouchInTabArea(444f, tabCenter, touchAreaHalf))
        assertFalse(isTouchInTabArea(155f, tabCenter, touchAreaHalf))
        assertFalse(isTouchInTabArea(445f, tabCenter, touchAreaHalf))
    }

    /**
     * Чистая функция, повторяющая логику [SettingsDrawerLayout.isTouchInTabArea],
     * но без зависимости от Android-ресурсов (density передаётся явно).
     */
    private fun isTouchInTabArea(
        touchY: Float,
        tabCenterY: Float,
        touchAreaHalf: Float,
    ): Boolean = touchY in (tabCenterY - touchAreaHalf)..(tabCenterY + touchAreaHalf)
}
