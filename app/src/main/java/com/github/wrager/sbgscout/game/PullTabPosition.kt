package com.github.wrager.sbgscout.game

/**
 * Преобразует UI-значение 0–100 % в реальную позицию pull-tab с учётом того, что тот
 * виден только в ограниченном диапазоне высоты экрана из-за status/nav bar.
 *
 * Диапазон видимости зависит от режима полноэкранного отображения:
 * - **Обычный** режим: видимая зона 5–92 % (status bar сверху, nav bar снизу)
 * - **Полноэкранный** режим: видимая зона 3–97 % (system bars скрыты)
 */
internal object PullTabPosition {
    const val MIN_VISIBLE_NORMAL = 5f
    const val MAX_VISIBLE_NORMAL = 92f
    const val MIN_VISIBLE_FULLSCREEN = 3f
    const val MAX_VISIBLE_FULLSCREEN = 97f

    /** @return актуальный процент (0–100) для применения к высоте экрана. */
    fun map(uiPercent: Int, fullscreen: Boolean): Float {
        val minVisible: Float
        val maxVisible: Float
        if (fullscreen) {
            minVisible = MIN_VISIBLE_FULLSCREEN
            maxVisible = MAX_VISIBLE_FULLSCREEN
        } else {
            minVisible = MIN_VISIBLE_NORMAL
            maxVisible = MAX_VISIBLE_NORMAL
        }
        return minVisible + (uiPercent / 100f) * (maxVisible - minVisible)
    }
}
