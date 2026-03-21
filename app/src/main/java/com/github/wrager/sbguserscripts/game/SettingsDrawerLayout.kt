package com.github.wrager.sbguserscripts.game

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * DrawerLayout, ограничивающий зону свайпа областью вокруг pull-tab.
 *
 * Стандартный DrawerLayout перехватывает edge swipe по всей высоте левого края,
 * что мешает взаимодействию с WebView. Этот подкласс пропускает только касания
 * в вертикальной зоне ±[TAB_TOUCH_AREA_HALF_DP] dp от центра таба.
 *
 * Также снижает порог открытия: drawer открывается после короткого свайпа
 * ([OPEN_SLIDE_THRESHOLD] от ширины drawer), а не стандартных 50%.
 */
class SettingsDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DrawerLayout(context, attrs, defStyleAttr) {

    /** Y-координата центра pull-tab в пикселях (устанавливается из Activity). */
    var tabCenterY: Float = 0f

    private var isDragging = false

    init {
        addDrawerListener(SnapOpenListener())
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // Ограничиваем зону касания только при закрытом drawer.
        // При открытом — закрытие свайпом работает из любой точки.
        val drawerClosed = !isDrawerOpen(GravityCompat.START)
        if (drawerClosed && event.actionMasked == MotionEvent.ACTION_DOWN && !isTouchInTabArea(event.y)) {
            return false
        }
        return super.onInterceptTouchEvent(event)
    }

    internal fun isTouchInTabArea(touchY: Float): Boolean {
        val touchAreaHalf = TAB_TOUCH_AREA_HALF_DP * resources.displayMetrics.density
        return touchY in (tabCenterY - touchAreaHalf)..(tabCenterY + touchAreaHalf)
    }

    /**
     * Listener, который открывает drawer при достижении короткого порога.
     *
     * Стандартный DrawerLayout требует протянуть ~50% ширины drawer.
     * Здесь: как только пользователь отпускает палец и drawer начинает settling,
     * при slideOffset > [OPEN_SLIDE_THRESHOLD] — принудительно открываем.
     */
    private inner class SnapOpenListener : SimpleDrawerListener() {
        private var peakOffset = 0f
        private var wasOpenOnDragStart = false

        override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {
            if (isDragging) {
                peakOffset = maxOf(peakOffset, slideOffset)
            }
        }

        override fun onDrawerStateChanged(newState: Int) {
            when (newState) {
                STATE_DRAGGING -> {
                    wasOpenOnDragStart = isDrawerOpen(GravityCompat.START)
                    isDragging = true
                    peakOffset = 0f
                }
                STATE_SETTLING -> {
                    // Принудительно открываем только если drawer был закрыт.
                    // Если drawer уже открыт и пользователь тянет влево — не мешаем закрытию.
                    if (isDragging && !wasOpenOnDragStart && peakOffset >= OPEN_SLIDE_THRESHOLD) {
                        openDrawer(GravityCompat.START)
                    }
                    isDragging = false
                }
                STATE_IDLE -> {
                    isDragging = false
                }
            }
        }
    }

    companion object {
        /** Половина высоты зоны касания вокруг центра таба (в dp). */
        internal const val TAB_TOUCH_AREA_HALF_DP = 48f

        /**
         * Минимальный slideOffset для принудительного открытия drawer.
         * Значение ~0.07 при ширине drawer 300dp ≈ 20dp свайпа —
         * достаточно, чтобы отличить от тапа.
         */
        internal const val OPEN_SLIDE_THRESHOLD = 0.07f
    }
}
