package com.github.wrager.sbgscout.e2e.screens

import kotlin.math.abs

/**
 * Положение нативного элемента поверх WebView относительно нижнего края экрана,
 * в пикселях экрана.
 *
 * @property shown элемент виден: у скрытого (`GONE`) нет положения на экране.
 * @property left левый край элемента.
 * @property right правый край элемента.
 * @property bottom нижний край элемента.
 * @property contentLeft левая граница содержимого `rootLayout`.
 * @property contentRight правая граница содержимого `rootLayout`.
 * @property safeBottom верхняя граница зоны навигационной панели и выреза экрана, в том числе
 *   скрытой панели в полноэкранном режиме.
 * @property margin ожидаемый отступ элемента от зоны навигационной панели; у индикатора загрузки
 *   страницы он же отступ от правого края.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
data class ViewPlacement(
    val shown: Boolean,
    val left: Int,
    val right: Int,
    val bottom: Int,
    val contentLeft: Int,
    val contentRight: Int,
    val safeBottom: Int,
    val margin: Int,
) {
    val isAboveNavigationBar: Boolean
        get() = shown && bottom == safeBottom - margin

    val isAtEnd: Boolean
        get() = contentRight - right == margin

    /** Сравнение сумм краёв, а не центров: при нечётной ширине центр попадает между пикселями. */
    val isCentered: Boolean
        get() = abs((left + right) - (contentLeft + contentRight)) <= 1
}
