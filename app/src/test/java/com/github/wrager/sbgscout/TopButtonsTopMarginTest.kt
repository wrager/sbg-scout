package com.github.wrager.sbgscout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-тесты [topButtonsTopMargin] — отступа блока кнопок у верхнего края
 * (страница входа) от верха содержимого `rootLayout` в GameActivity.
 */
class TopButtonsTopMarginTest {
    @Test
    fun `root padding covers safe zone so margin is only the gap`() {
        // Обычный режим: отступ rootLayout сверху равен высоте статус-бара.
        assertEquals(GAP, topButtonsTopMargin(safeTopInset = 80, rootPaddingTop = 80, gap = GAP))
    }

    @Test
    fun `without root padding margin skips the whole safe zone`() {
        // Полноэкранный режим: статус-бар скрыт, отступа у rootLayout нет,
        // но по свайпу статус-бар выезжает поверх контента.
        assertEquals(80 + GAP, topButtonsTopMargin(safeTopInset = 80, rootPaddingTop = 0, gap = GAP))
    }

    @Test
    fun `cutout deeper than root padding adds the difference`() {
        assertEquals(20 + GAP, topButtonsTopMargin(safeTopInset = 100, rootPaddingTop = 80, gap = GAP))
    }

    @Test
    fun `root padding larger than safe zone does not reduce the gap`() {
        assertEquals(GAP, topButtonsTopMargin(safeTopInset = 0, rootPaddingTop = 80, gap = GAP))
    }

    private companion object {
        const val GAP = 22
    }
}
