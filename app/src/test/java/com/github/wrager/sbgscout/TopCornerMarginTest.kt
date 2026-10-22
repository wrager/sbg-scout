package com.github.wrager.sbgscout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-тесты [topCornerMargin] - отступа индикатора загрузки страницы в правом
 * верхнем углу от верха содержимого `rootLayout` в GameActivity.
 */
class TopCornerMarginTest {
    @Test
    fun `root padding covers safe zone so margin is only the corner margin`() {
        // Обычный режим: отступ rootLayout сверху равен высоте статус-бара.
        assertEquals(MARGIN, topCornerMargin(safeTopInset = 80, rootPaddingTop = 80, margin = MARGIN))
    }

    @Test
    fun `without root padding margin skips the whole safe zone`() {
        // Полноэкранный режим: статус-бар скрыт, отступа у rootLayout нет,
        // но по свайпу статус-бар выезжает поверх контента.
        assertEquals(80 + MARGIN, topCornerMargin(safeTopInset = 80, rootPaddingTop = 0, margin = MARGIN))
    }

    @Test
    fun `cutout deeper than root padding adds the difference`() {
        assertEquals(20 + MARGIN, topCornerMargin(safeTopInset = 100, rootPaddingTop = 80, margin = MARGIN))
    }

    @Test
    fun `root padding larger than safe zone does not reduce the margin`() {
        assertEquals(MARGIN, topCornerMargin(safeTopInset = 0, rootPaddingTop = 80, margin = MARGIN))
    }

    private companion object {
        const val MARGIN = 33
    }
}
