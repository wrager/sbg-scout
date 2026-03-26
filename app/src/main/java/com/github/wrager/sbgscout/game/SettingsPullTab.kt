package com.github.wrager.sbgscout.game

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Визуальный индикатор pull-tab для выдвижной панели настроек.
 *
 * Рисует сегмент эллипса (выпуклость влево), торчащий из правого края экрана.
 * Внутри — шеврон «<». Не обрабатывает touch — жест свайпа перехватывает
 * [SettingsDrawerLayout].
 *
 * Позиция по X обновляется извне через [translationX] при скольжении drawer.
 */
class SettingsPullTab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * Все touch events проходят насквозь в WebView/DrawerLayout.
     * Таб — чисто визуальный элемент, жесты обрабатывает [SettingsDrawerLayout].
     */
    @SuppressLint("ClickableViewAccessibility") // Таб не интерактивен, тапы проходят в WebView
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TAB_COLOR
        style = Paint.Style.FILL
    }

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHEVRON_COLOR
        style = Paint.Style.STROKE
        strokeWidth = CHEVRON_STROKE_WIDTH_DP * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arcPath = Path()
    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawArc(canvas)
        drawChevron(canvas)
    }

    /**
     * Рисуем левую половину эллипса: овал смещён вправо так, что видна только
     * выпуклая левая часть шириной = ширине View.
     */
    private fun drawArc(canvas: Canvas) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Овал: ширина = viewWidth * 2 (чтобы левая половина = viewWidth),
        // сдвинут вправо на viewWidth
        arcRect.set(0f, 0f, viewWidth * 2f, viewHeight)
        arcPath.reset()
        arcPath.addArc(arcRect, 90f, 180f)
        arcPath.close()

        canvas.drawPath(arcPath, arcPaint)
    }

    /**
     * Направление шеврона: `false` → «<» (drawer закрыт),
     * `true` → «>» (drawer открыт).
     */
    var isOpen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Рисуем шеврон «<» или «>» по центру видимой области. */
    private fun drawChevron(canvas: Canvas) {
        val centerX = width * (1f - CHEVRON_CENTER_X_FRACTION)
        val centerY = height / 2f
        val armLength = CHEVRON_ARM_LENGTH_DP * resources.displayMetrics.density

        // Направление: «<» — кончик влево (закрыт), «>» — кончик вправо (открыт)
        val direction = if (isOpen) 1f else -1f

        canvas.drawLine(
            centerX - direction * armLength / 2f,
            centerY - armLength,
            centerX + direction * armLength / 2f,
            centerY,
            chevronPaint,
        )
        canvas.drawLine(
            centerX + direction * armLength / 2f,
            centerY,
            centerX - direction * armLength / 2f,
            centerY + armLength,
            chevronPaint,
        )
    }

    companion object {
        private const val TAB_COLOR = 0x40_6B_6B_6B.toInt()
        private const val CHEVRON_COLOR = 0x80_FF_FF_FF.toInt()
        private const val CHEVRON_STROKE_WIDTH_DP = 1.5f
        private const val CHEVRON_ARM_LENGTH_DP = 5f

        /** Шеврон в визуальном центре полуэллипса (центроид ≈ 4/(3π) ≈ 0.42). */
        private const val CHEVRON_CENTER_X_FRACTION = 0.42f
    }
}
