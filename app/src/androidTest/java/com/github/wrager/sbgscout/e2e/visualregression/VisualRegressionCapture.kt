package com.github.wrager.sbgscout.e2e.visualregression

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.github.wrager.sbgscout.e2e.screenshots.ScrollStitchCapture

/**
 * Снимает actual.png для visual regression в native-разрешении эмулятора
 * (1080x2280 для Pixel 4 portrait), без масштабирования.
 *
 * AGP при `useTestStorageService=true` автоматически копирует файлы из
 * `PlatformTestStorageRegistry` после прогона `connectedInstrAndroidTest` в
 * `app/build/outputs/connected_android_test_additional_output/`. Host-side
 * gradle-задачи `compareVisualRegressionBaselines` или
 * `copyVisualRegressionBaselines` далее обрабатывают эти файлы.
 *
 * Native-разрешение принципиально для VR (а не 360px как в README): pixel-diff
 * чувствителен к anti-aliasing и font-hinting, которые `Bitmap.createScaledBitmap`
 * сглаживает. Низкоуровневые операции захвата делегируются в
 * [ScrollStitchCapture].
 */
object VisualRegressionCapture {

    /**
     * Снимает экран целиком (без scroll-stitching) - подходит для одного
     * viewport'а Activity/Fragment, диалогов, login-страницы. Ширина и высота
     * actual.png равны разрешению эмулятора.
     */
    fun captureFullNative(name: String) {
        val bitmap = ScrollStitchCapture.takeScreenshot()
        try {
            writeActualPng(name, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Снимает указанный регион [region] (в физических пикселях) - для
     * captures, ограниченных областью WebView popup или Dialog'а. [region]
     * клампится размерами экрана.
     */
    fun captureRegionNative(name: String, region: Rect) {
        val full = ScrollStitchCapture.takeScreenshot()
        try {
            val safe = clamp(region, full.width, full.height)
            val cropped = Bitmap.createBitmap(
                full,
                safe.left,
                safe.top,
                safe.width(),
                safe.height(),
            )
            try {
                writeActualPng(name, cropped)
            } finally {
                cropped.recycle()
            }
        } finally {
            full.recycle()
        }
    }

    /**
     * Снимает прокручиваемый контент [scrollable] и склеивает frames в длинный
     * bitmap - для PreferenceFragmentCompat (settings overlay), ScriptManager.
     * Stitching-логика - в [ScrollStitchCapture.captureScrollableStitched].
     */
    fun captureScrollableNative(name: String, scrollable: RecyclerView) {
        val stitched = ScrollStitchCapture.captureScrollableStitched(scrollable)
        try {
            writeActualPng(name, stitched)
        } finally {
            stitched.recycle()
        }
    }

    private fun writeActualPng(name: String, bitmap: Bitmap) {
        val storage = PlatformTestStorageRegistry.getInstance()
        storage.openOutputFile("visual-regression/$name.actual.png").use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun clamp(region: Rect, maxWidth: Int, maxHeight: Int): Rect {
        val left = region.left.coerceIn(0, maxWidth - 1)
        val top = region.top.coerceIn(0, maxHeight - 1)
        val right = region.right.coerceIn(left + 1, maxWidth)
        val bottom = region.bottom.coerceIn(top + 1, maxHeight)
        return Rect(left, top, right, bottom)
    }
}
