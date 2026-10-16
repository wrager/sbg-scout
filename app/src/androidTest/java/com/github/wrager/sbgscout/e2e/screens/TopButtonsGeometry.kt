package com.github.wrager.sbgscout.e2e.screens

import kotlin.math.abs

/**
 * Положение блока кнопок поверх WebView (`topButtonsContainer`) в экранных пикселях.
 *
 * @property top верх блока.
 * @property centerY центр блока по вертикали.
 * @property safeTop нижняя граница зоны статус-бара и выреза экрана, в том числе
 *   скрытого статус-бара в полноэкранном режиме.
 * @property areaCenterY центр области `rootLayout` без отступов, по которой
 *   центрируется блок.
 * @property screenTop верх `rootLayout`.
 * @property screenHeight высота `rootLayout`.
 */
data class TopButtonsGeometry(
    val top: Int,
    val centerY: Int,
    val safeTop: Int,
    val areaCenterY: Int,
    val screenTop: Int,
    val screenHeight: Int,
) {
    /** У верхнего края: не заходит под статус-бар и вырез, верх — в верхней четверти экрана. */
    val isAtTop: Boolean
        get() = top >= safeTop && top < screenTop + screenHeight / 4

    /** По центру области под системными панелями, с точностью до округления. */
    val isCentered: Boolean
        get() = abs(centerY - areaCenterY) <= CENTER_TOLERANCE_PX

    private companion object {
        const val CENTER_TOLERANCE_PX = 2
    }
}
