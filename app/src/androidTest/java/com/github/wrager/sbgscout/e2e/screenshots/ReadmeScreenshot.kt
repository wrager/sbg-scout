package com.github.wrager.sbgscout.e2e.screenshots

import android.app.LocaleManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Маркер тестов, генерирующих README-скриншоты.
 *
 * Gradle task [generateReadmeScreenshots][README.md] фильтрует прогон по
 * этой аннотации (`-e annotation`), чтобы при ручной перегенерации не гонять
 * 30+ остальных e2e-сценариев.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ReadmeScreenshot

/**
 * Снимает скриншоты для README и пишет PNG в test storage.
 *
 * AGP при `useTestStorageService=true` автоматически копирует файлы из
 * `PlatformTestStorageRegistry` после прогона `connectedInstrAndroidTest` в
 * `app/build/outputs/connected_android_test_additional_output/<device>/test_runfiles/`.
 * Gradle task `generateReadmeScreenshots` далее переносит их в
 * `.github/images/screenshots/`.
 *
 * Все методы блокирующие: [takeScreenshot] синхронен, [evaluateJavascript]
 * оборачивается в CountDownLatch с timeout.
 */
object ReadmeScreenshotCapture {

    /**
     * Целевая ширина PNG — 360 px (mdpi-эквивалент).
     * Текущие ручные скриншоты в README сделаны под эту ширину
     * (game_settings.png 360x193, settings.png 360x1208, script-manager.png 360x732),
     * её надо сохранить, чтобы не сломать вёрстку README.
     */
    const val TARGET_WIDTH_PX = 360

    private const val EVAL_TIMEOUT_MS = 5_000L

    /**
     * Принудительно ставит русскую локаль приложения через
     * [LocaleManager.setApplicationLocales] (API 33+, sync, persistent).
     * Должна вызываться ДО первого `launchGameActivity()` в тесте, чтобы
     * Activity сразу при создании наследовала русскую локаль.
     *
     * `LocaleManager` сохраняет локаль persistent (выживает Activity recreate
     * и process restart), поэтому в `@After` обязательно вызывать
     * [resetLocale], иначе следующие e2e-тесты получат русскую локаль и
     * могут быть проблемы с асертами на английские строки.
     *
     * Эмулятор для нашего CI — API 33 (см. project memory), `LocaleManager`
     * есть. Для локального запуска на API < 33 этот вызов будет no-op,
     * скриншоты получатся на системной локали эмулятора.
     */
    fun forceRussianLocale() {
        val localeManager = localeManager() ?: return
        localeManager.applicationLocales = LocaleList.forLanguageTags("ru")
    }

    fun resetLocale() {
        val localeManager = localeManager() ?: return
        localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    private fun localeManager(): LocaleManager? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return null
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
    }

    fun captureFullScreen(name: String) {
        val bitmap = takeScreenshot()
        try {
            writePng(name, scaleToTargetWidth(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    fun captureRegion(name: String, region: Rect) {
        val full = takeScreenshot()
        try {
            val safeRegion = clamp(region, full.width, full.height)
            val cropped = Bitmap.createBitmap(
                full,
                safeRegion.left,
                safeRegion.top,
                safeRegion.width(),
                safeRegion.height(),
            )
            try {
                writePng(name, scaleToTargetWidth(cropped))
            } finally {
                cropped.recycle()
            }
        } finally {
            full.recycle()
        }
    }

    /**
     * Возвращает экранные координаты (физические пиксели) элемента в [webView]
     * по CSS-селектору. Используется для crop в [captureRegion].
     *
     * Реализация: `getBoundingClientRect()` даёт CSS-координаты относительно
     * viewport WebView. Умножаем на density (= window.devicePixelRatio в
     * Android WebView при viewport meta `width=device-width`) и прибавляем
     * экранную позицию WebView, полученную из `View.getLocationOnScreen` —
     * она уже в физических пикселях (то же координатное пространство, что
     * у `UiAutomation.takeScreenshot()`).
     */
    fun webElementBoundsInScreen(webView: WebView, cssSelector: String): Rect {
        val script = """
            (function() {
                var el = document.querySelector(${cssSelector.toJsLiteral()});
                if (!el) return JSON.stringify({error: 'not found'});
                var r = el.getBoundingClientRect();
                return JSON.stringify({
                    left: r.left, top: r.top, width: r.width, height: r.height
                });
            })()
        """.trimIndent()
        return webBoundsInScreen(webView, script)
    }

    /**
     * Возвращает экранные координаты прямоугольника, вычисленного через произвольный
     * JS-сценарий [scriptReturningRectJson]. Скрипт обязан вернуть JSON со строковыми
     * полями `left`, `top`, `width`, `height` (в CSS-пикселях относительно viewport)
     * либо `{"error": "..."}`. Для случаев сложнее одного `querySelector`,
     * например crop по нескольким соседним элементам с вычисленным bounding box.
     */
    fun webBoundsInScreen(webView: WebView, scriptReturningRectJson: String): Rect {
        val raw = evaluateJavascript(webView, scriptReturningRectJson)
        // evaluateJavascript возвращает результат как JSON-строку (`"{...}"`),
        // т.е. сначала анэскейпим уровень "выходной строки".
        val unwrapped = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            raw
        }
        val obj = JSONObject(unwrapped)
        if (obj.has("error")) {
            error("webBoundsInScreen: ${obj.getString("error")}")
        }
        val cssLeft = obj.getDouble("left").toFloat()
        val cssTop = obj.getDouble("top").toFloat()
        val cssWidth = obj.getDouble("width").toFloat()
        val cssHeight = obj.getDouble("height").toFloat()

        val location = IntArray(2)
        var density = 1f
        runOnUi {
            webView.getLocationOnScreen(location)
            density = webView.resources.displayMetrics.density
        }
        val left = (location[0] + cssLeft * density).toInt()
        val top = (location[1] + cssTop * density).toInt()
        val width = (cssWidth * density).toInt()
        val height = (cssHeight * density).toInt()
        return Rect(left, top, left + width, top + height)
    }

    /**
     * Возвращает экранные координаты `View` (физические пиксели).
     * Используется для crop overlay-секций (settings, script manager).
     */
    fun viewBoundsInScreen(view: View): Rect {
        val location = IntArray(2)
        var width = 0
        var height = 0
        runOnUi {
            view.getLocationOnScreen(location)
            width = view.width
            height = view.height
        }
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    /**
     * Снимает экран целиком, включая прокручиваемый за viewport контент
     * [scrollable]. Для каждого frame используется `UiAutomation.takeScreenshot()`
     * (даёт system bars, тени, footer Activity), потом frames склеиваются в
     * длинный PNG.
     *
     * Алгоритм:
     * 1. Прокручиваем [scrollable] на самый верх и снимаем frame[0].
     * 2. Пока [scrollable] может скроллиться вниз - scrollBy на высоту
     *    [scrollable], ждём idle, снимаем frame[i].
     * 3. Stitching: header (от 0 до [scrollable].top из frame[0]) + viewport
     *    из каждого frame (от [scrollable].top до [scrollable].bottom) +
     *    footer (от [scrollable].bottom до низа экрана из последнего frame).
     *
     * Off-screen `view.draw(Canvas)` не подходит: software canvas не
     * рендерит elevation-shadows (тени MaterialCardView), не показывает
     * status/navigation bars и Activity-уровень footer (например, кнопку
     * закрытия overlay). Через UiAutomation все эти слои попадают.
     *
     * Для WebView не работает: WebView рисуется как single texture, и его
     * прокрутка не идентична scrollable native-view. Для веб-фрагментов
     * используйте [captureRegion] + [webBoundsInScreen].
     */
    fun captureFullScreenWithScroll(name: String, scrollable: RecyclerView) {
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
        // Сколько физических пикселей фактически промотано в каждом frame
        // (для frame[0] всегда 0; для остальных - actualScroll). Нужно для
        // обрезки overlap'а на последнем frame: RecyclerView под конец списка
        // отдаёт scrollBy меньше, чем запросили (overshoot prevention),
        // и без обрезки в stitched попадают повторяющиеся items.
        val actualScrolls = mutableListOf<Int>()
        try {
            frames += takeScreenshot()
            actualScrolls += 0

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
                actualScrolls += (after - before).coerceIn(0, viewportHeight)
                frames += takeScreenshot()
                stepCount++
            }

            val first = frames[0]
            val last = frames.last()
            val srcWidth = first.width
            // Высота итогового изображения: header + первый viewport (полный) +
            // фактический scroll-amount каждого следующего frame + footer.
            val viewportsHeight = viewportHeight + actualScrolls.drop(1).sum()
            val totalHeight = topY + viewportsHeight + (last.height - bottomY)
            val stitched = Bitmap.createBitmap(
                srcWidth,
                totalHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            try {
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
                    if (i == 0) {
                        canvas.drawBitmap(
                            frame,
                            Rect(0, topY, srcWidth, bottomY),
                            Rect(0, dstTop, srcWidth, dstTop + viewportHeight),
                            null,
                        )
                        dstTop += viewportHeight
                    } else {
                        // На overshoot последнего scroll виден только
                        // actualScroll новых пикселей внизу viewport-а;
                        // верхняя часть viewport в этом frame дублирует
                        // нижнюю часть предыдущего frame и должна быть
                        // выкинута.
                        val advance = actualScrolls[i]
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
                }
                if (last.height > bottomY) {
                    canvas.drawBitmap(
                        last,
                        Rect(0, bottomY, srcWidth, last.height),
                        Rect(0, dstTop, srcWidth, totalHeight),
                        null,
                    )
                }
                writePng(name, scaleToTargetWidth(stitched))
            } finally {
                stitched.recycle()
            }
        } finally {
            frames.forEach { it.recycle() }
        }
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

    private fun takeScreenshot(): Bitmap {
        return InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot() вернул null")
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        val storage = PlatformTestStorageRegistry.getInstance()
        storage.openOutputFile("readme/$name.png").use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun scaleToTargetWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width == TARGET_WIDTH_PX) return bitmap
        val scale = TARGET_WIDTH_PX.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH_PX, targetHeight, true)
    }

    private fun clamp(region: Rect, maxWidth: Int, maxHeight: Int): Rect {
        val left = region.left.coerceIn(0, maxWidth - 1)
        val top = region.top.coerceIn(0, maxHeight - 1)
        val right = region.right.coerceIn(left + 1, maxWidth)
        val bottom = region.bottom.coerceIn(top + 1, maxHeight)
        return Rect(left, top, right, bottom)
    }

    private fun evaluateJavascript(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        runOnUi {
            webView.evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        check(latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "evaluateJavascript timeout (${EVAL_TIMEOUT_MS}ms): $script"
        }
        return result.get() ?: "null"
    }

    private fun runOnUi(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun String.toJsLiteral(): String =
        "'" + this.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
