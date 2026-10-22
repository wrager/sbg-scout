package com.github.wrager.sbgscout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-тесты [edgeMargin] - отступа блока нативных кнопок и индикатора загрузки
 * страницы от нижнего края содержимого `rootLayout` в GameActivity.
 */
class EdgeMarginTest {
    @Test
    fun `root padding covers safe zone so margin is only the edge margin`() {
        // Обычный режим: отступ rootLayout снизу равен высоте навигационной панели.
        assertEquals(MARGIN, edgeMargin(safeInset = 80, rootPadding = 80, margin = MARGIN))
    }

    @Test
    fun `without root padding margin skips the whole safe zone`() {
        // Полноэкранный режим: панель скрыта, отступа у rootLayout нет, но по свайпу она выезжает поверх контента.
        assertEquals(80 + MARGIN, edgeMargin(safeInset = 80, rootPadding = 0, margin = MARGIN))
    }

    @Test
    fun `cutout deeper than root padding adds the difference`() {
        assertEquals(20 + MARGIN, edgeMargin(safeInset = 100, rootPadding = 80, margin = MARGIN))
    }

    @Test
    fun `root padding larger than safe zone does not reduce the margin`() {
        // Клавиатура: отступ rootLayout снизу выше навигационной панели.
        assertEquals(MARGIN, edgeMargin(safeInset = 80, rootPadding = 300, margin = MARGIN))
    }

    private companion object {
        const val MARGIN = 33
    }
}
