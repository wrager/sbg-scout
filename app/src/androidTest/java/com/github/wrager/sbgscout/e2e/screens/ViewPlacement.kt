package com.github.wrager.sbgscout.e2e.screens

import kotlin.math.abs

/**
 * Положение нативного элемента поверх WebView относительно содержимого окна и
 * системных панелей, в пикселях экрана.
 *
 * @property shown элемент виден: у скрытого (`GONE`) нет положения на экране.
 * @property left левый край элемента.
 * @property right правый край элемента.
 * @property top верхний край элемента.
 * @property bottom нижний край элемента.
 * @property contentLeft левая граница содержимого `rootLayout`.
 * @property contentRight правая граница содержимого `rootLayout`.
 * @property contentTop верхняя граница содержимого `rootLayout`.
 * @property contentBottom нижняя граница содержимого `rootLayout`, с учётом клавиатуры.
 * @property safeBottom верхняя граница зоны навигационной панели и выреза экрана, в том числе
 *   скрытой панели в полноэкранном режиме.
 * @property margin нижний опорный отступ блока кнопок либо фактический отступ индикатора загрузки
 *   страницы от зоны навигационной панели и правого края.
 *
 * 🤖 Generated with GPT-6 Sol
 */
data class ViewPlacement(
    val shown: Boolean,
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int,
    val contentLeft: Int,
    val contentRight: Int,
    val contentTop: Int,
    val contentBottom: Int,
    val safeBottom: Int,
    val margin: Int,
) {
    val isAboveNavigationBar: Boolean
        get() = shown && bottom == safeBottom - margin

    val isAtEnd: Boolean
        get() = contentRight - right == margin

    val isHalfwayBetweenCenterAndBottom: Boolean
        get() {
            val bottomReference = minOf(contentBottom, safeBottom) - margin
            val expectedBottomFourfold = contentTop + contentBottom + bottom - top + 2 * bottomReference
            return shown && abs(4 * bottom - expectedBottomFourfold) <= 4
        }

    /** Сравнение сумм краёв, а не центров: при нечётной ширине центр попадает между пикселями. */
    val isCentered: Boolean
        get() = abs((left + right) - (contentLeft + contentRight)) <= 1
}
