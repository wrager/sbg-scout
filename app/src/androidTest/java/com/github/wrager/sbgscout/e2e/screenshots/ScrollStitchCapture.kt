package com.github.wrager.sbgscout.e2e.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Низкоуровневые операции захвата экрана для скриншот-тестов: снимок через
 * `UiAutomation.takeScreenshot()` и stitching прокручиваемой области в
 * длинный bitmap.
 *
 * Без масштабирования и без записи на диск - вызывающая сторона решает, что
 * делать с результатом (README-тесты сохраняют сжатый PNG в 360px-ширину для
 * вёрстки документа, visual-regression-тесты пишут native-разрешение для
 * pixel-diff). Pixel-overlap detection при stitching уже есть здесь
 * ([pixelsTolerantMatch]) и переиспользуется в обеих ветках.
 */
object ScrollStitchCapture {

    /**
     * Снимает экран целиком через `UiAutomation.takeScreenshot()` (физические
     * пиксели, включает status bar, system insets, тени Activity).
     */
    fun takeScreenshot(): Bitmap {
        return InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot() вернул null")
    }

    /**
     * Снимает экран целиком, включая прокручиваемый за viewport контент
     * [scrollable]. Для каждого frame используется [takeScreenshot] (даёт
     * system bars, тени, footer Activity), потом frames склеиваются в длинный
     * bitmap.
     *
     * Алгоритм:
     * 1. Прокручиваем [scrollable] на самый верх и снимаем frame[0].
     * 2. Пока [scrollable] может скроллиться вниз - scrollBy на высоту
     *    [scrollable], ждём idle, снимаем frame[i].
     * 3. Stitching: header (от 0 до [scrollable].top из frame[0]) + viewport
     *    из каждого frame (от [scrollable].top до [scrollable].bottom) +
     *    footer (от [scrollable].bottom до низа экрана из последнего frame).
     *
     * Off-screen `view.draw(Canvas)` не подходит: software canvas не рендерит
     * elevation-shadows (тени MaterialCardView), не показывает status/navigation
     * bars и Activity-уровень footer (например, кнопку закрытия overlay). Через
     * UiAutomation все эти слои попадают.
     *
     * Для WebView не работает: WebView рисуется как single texture, и его
     * прокрутка не идентична scrollable native-view. Для веб-фрагментов
     * используйте захват региона + JS-вычисление bounds.
     *
     * Возвращённый bitmap имеет ширину одного frame'а (физическое разрешение
     * экрана) и высоту = topY + sum(advances) + (lastFrameHeight - bottomY).
     * Вызывающая сторона ответственна за recycle.
     */
    fun captureScrollableStitched(scrollable: RecyclerView): Bitmap {
        val instr = InstrumentationRegistry.getInstrumentation()

        // Скроллбар RecyclerView фейдит in/out при scroll; на разных
        // последовательных frame'ах он попадает в разной фазе alpha и в
        // stitched изображении дублируется ёлочкой. Отключаем на время
        // съёмки, восстанавливаем в finally.
        var origScrollbarEnabled = false
        instr.runOnMainSync {
            origScrollbarEnabled = scrollable.isVerticalScrollBarEnabled
            scrollable.isVerticalScrollBarEnabled = false
        }

        try {
            return captureScrollableStitchedInternal(scrollable)
        } finally {
            instr.runOnMainSync {
                scrollable.isVerticalScrollBarEnabled = origScrollbarEnabled
            }
        }
    }

    private fun captureScrollableStitchedInternal(scrollable: RecyclerView): Bitmap {
        val instr = InstrumentationRegistry.getInstrumentation()

        var topY = 0
        var bottomY = 0
        instr.runOnMainSync {
            val loc = IntArray(2)
            scrollable.getLocationOnScreen(loc)
            topY = loc[1]
            bottomY = loc[1] + scrollable.height
            scrollable.scrollToPosition(0)
        }
        instr.waitForIdleSync()
        Thread.sleep(SCROLL_SETTLE_MS)

        val frames = mutableListOf<Bitmap>()
        // Каждому frame[i] (i>0) сохраняем фактический сдвиг RV в физических
        // пикселях: после scrollBy(viewportHeight) RecyclerView в конце списка
        // отдаёт меньше (overshoot prevention) - в этом случае overlap =
        // viewportHeight - actualScroll. Используется как fallback, если
        // pixel-match не нашёл совпадения (бывает на subpixel-render diff
        // между frame'ами, которое tolerant-проверка тоже не покрывает).
        val scrollDiffs = mutableListOf<Int>()
        try {
            frames += takeScreenshot()
            scrollDiffs += 0

            val viewportHeight = bottomY - topY
            check(viewportHeight > 0) {
                "RecyclerView имеет нулевую высоту, scroll-stitch невозможен"
            }
            var stepCount = 0
            while (canScrollDown(scrollable) && stepCount < MAX_SCROLL_STEPS) {
                var before = 0
                instr.runOnMainSync {
                    before = scrollable.computeVerticalScrollOffset()
                    scrollable.scrollBy(0, viewportHeight)
                }
                instr.waitForIdleSync()
                Thread.sleep(SCROLL_SETTLE_MS)
                var after = 0
                instr.runOnMainSync {
                    after = scrollable.computeVerticalScrollOffset()
                }
                scrollDiffs += (after - before).coerceIn(0, viewportHeight)
                frames += takeScreenshot()
                stepCount++
            }

            // advances[0] = viewportHeight (первый frame целиком новый).
            // Для последующих сначала пытаемся pixel-match (точный), при 0
            // возвращаемся к scroll-offset (приблизительный, но детерминированный).
            // Pixel-match strict не всегда срабатывает на subpixel-rendering
            // или GPU cache, tolerant-проверка с допуском 8/channel помогает,
            // но тоже не всегда; scroll-offset как fallback гарантирует, что
            // не пропустится overshoot последнего scroll'а - именно из-за
            // этого в script-manager.png дублировался весь блок с карточками.
            val advances = IntArray(frames.size)
            advances[0] = viewportHeight
            for (i in 1 until frames.size) {
                val overlap = findVerticalOverlap(
                    frames[i - 1],
                    frames[i],
                    topY,
                    bottomY,
                    maxOverlap = viewportHeight - 1,
                )
                advances[i] = if (overlap > 0) {
                    (viewportHeight - overlap).coerceAtLeast(0)
                } else {
                    scrollDiffs[i].coerceIn(0, viewportHeight)
                }
            }

            val first = frames[0]
            val last = frames.last()
            val srcWidth = first.width
            val viewportsHeight = advances.sum()
            val totalHeight = topY + viewportsHeight + (last.height - bottomY)
            val stitched = Bitmap.createBitmap(
                srcWidth,
                totalHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(stitched)
            if (topY > 0) {
                canvas.drawBitmap(
                    first,
                    Rect(0, 0, srcWidth, topY),
                    Rect(0, 0, srcWidth, topY),
                    null,
                )
            }
            var dstTop = topY
            for ((i, frame) in frames.withIndex()) {
                val advance = advances[i]
                if (advance <= 0) continue
                val srcTop = bottomY - advance
                canvas.drawBitmap(
                    frame,
                    Rect(0, srcTop, srcWidth, bottomY),
                    Rect(0, dstTop, srcWidth, dstTop + advance),
                    null,
                )
                dstTop += advance
            }
            if (last.height > bottomY) {
                canvas.drawBitmap(
                    last,
                    Rect(0, bottomY, srcWidth, last.height),
                    Rect(0, dstTop, srcWidth, totalHeight),
                    null,
                )
            }
            return stitched
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    /**
     * Находит количество пикселей по вертикали, на которое верхняя часть
     * RV-области [curr] дублирует нижнюю часть RV-области [prev]. Сначала
     * strict-проверка по contentEquals (быстрая, точная), при неудаче -
     * tolerant-проверка с per-channel разницей до [PIXEL_TOLERANCE_PER_CHANNEL]:
     * subtle отличия от anti-aliasing, alpha-композитинга теней и render
     * cache могут давать пиксели "почти-одинаковые", но strict equal не
     * срабатывает - это давало ложный overlap=0 и дубли в stitched.
     *
     * 0 = overlap'а нет ни strict, ни tolerant - скролл реально прошёл на
     * полный viewport.
     */
    private fun findVerticalOverlap(
        prev: Bitmap,
        curr: Bitmap,
        rvTop: Int,
        rvBottom: Int,
        maxOverlap: Int,
    ): Int {
        val width = prev.width
        val rvHeight = rvBottom - rvTop
        val checkLimit = minOf(maxOverlap, rvHeight)
        if (checkLimit <= 0) return 0
        for (overlap in checkLimit downTo 1) {
            val prevPixels = IntArray(width * overlap)
            prev.getPixels(prevPixels, 0, width, 0, rvBottom - overlap, width, overlap)
            val currPixels = IntArray(width * overlap)
            curr.getPixels(currPixels, 0, width, 0, rvTop, width, overlap)
            if (prevPixels.contentEquals(currPixels)) return overlap
        }
        for (overlap in checkLimit downTo 1) {
            val prevPixels = IntArray(width * overlap)
            prev.getPixels(prevPixels, 0, width, 0, rvBottom - overlap, width, overlap)
            val currPixels = IntArray(width * overlap)
            curr.getPixels(currPixels, 0, width, 0, rvTop, width, overlap)
            if (pixelsTolerantMatch(prevPixels, currPixels)) return overlap
        }
        return 0
    }

    private const val PIXEL_TOLERANCE_PER_CHANNEL = 8

    private fun pixelsTolerantMatch(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val p1 = a[i]
            val p2 = b[i]
            if (p1 == p2) continue
            val dr = kotlin.math.abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
            if (dr > PIXEL_TOLERANCE_PER_CHANNEL) return false
            val dg = kotlin.math.abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
            if (dg > PIXEL_TOLERANCE_PER_CHANNEL) return false
            val db = kotlin.math.abs((p1 and 0xFF) - (p2 and 0xFF))
            if (db > PIXEL_TOLERANCE_PER_CHANNEL) return false
        }
        return true
    }

    private fun canScrollDown(view: RecyclerView): Boolean {
        var canScroll = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            canScroll = view.canScrollVertically(1)
        }
        return canScroll
    }

    private const val SCROLL_SETTLE_MS = 200L
    private const val MAX_SCROLL_STEPS = 40
}
