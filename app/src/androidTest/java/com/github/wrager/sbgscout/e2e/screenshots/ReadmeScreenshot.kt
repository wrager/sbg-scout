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
     * Снимает [view] целиком, включая прокручиваемый за viewport контент.
     * `UiAutomation.takeScreenshot()` берёт только видимую область экрана —
     * для длинного PreferenceFragmentCompat / RecyclerView этого мало.
     *
     * Алгоритм:
     * 1. Прокручиваем все вложенные RecyclerView через каждую позицию, чтобы
     *    LayoutManager создал и забиндил ViewHolder'ы для всех items
     *    (RecyclerView lazy-биндит, без этого `view.draw` нарисует только
     *    те, что были привязаны к моменту вызова).
     * 2. Измеряем view с `MeasureSpec.UNSPECIFIED` высотой → получаем полный
     *    `measuredHeight` всего вложенного контента.
     * 3. Перекладываем view (`layout(0, 0, w, fullHeight)`) и рисуем на
     *    bitmap через `view.draw(Canvas)`.
     * 4. Возвращаем view к исходным размерам, чтобы Activity не сломалась
     *    в @After.
     *
     * Не работает для WebView с прокручиваемой страницей: WebView рисуется
     * как single texture (visible content). Для веб-фрагментов используйте
     * [captureRegion] + [webElementBoundsInScreen].
     */
    fun captureViewFullContent(name: String, view: View) {
        // Шаг 1: forceful binding всех RecyclerView ВНЕ блока с draw, чтобы
        // promoted layout-passes между scrollToPosition прошли через очередь
        // main thread.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            collectRecyclerViews(view).forEach { rv ->
                val count = rv.adapter?.itemCount ?: 0
                for (i in 0 until count) {
                    rv.scrollToPosition(i)
                }
                rv.scrollToPosition(0)
            }
        }
        instrumentation.waitForIdleSync()

        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val width = view.width
            check(width > 0) { "view.width=0 — view не отлайаучен, скриншот невозможен" }

            // Сохранить оригинальные границы.
            val origLeft = view.left
            val origTop = view.top
            val origRight = view.right
            val origBottom = view.bottom

            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(widthSpec, heightSpec)
            val fullHeight = view.measuredHeight.coerceAtLeast(1)

            // Перелайаут с full height. Координаты origLeft/origTop сохраняются,
            // чтобы после восстановления Activity-иерархия не уехала.
            view.layout(origLeft, origTop, origLeft + width, origTop + fullHeight)
            // Сбросить scrollY всех скроллируемых child'ов в начало, чтобы
            // нарисовать с верха.
            view.scrollTo(0, 0)
            collectRecyclerViews(view).forEach { it.scrollToPosition(0) }

            val bmp = Bitmap.createBitmap(width, fullHeight, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            bitmap = bmp

            // Восстановить оригинальный layout, чтобы tearDown не падал.
            view.layout(origLeft, origTop, origRight, origBottom)
        }

        val bmp = bitmap ?: error("captureViewFullContent: bitmap == null")
        try {
            writePng(name, scaleToTargetWidth(bmp))
        } finally {
            bmp.recycle()
        }
    }

    private fun collectRecyclerViews(
        root: View,
        into: MutableList<RecyclerView> = mutableListOf(),
    ): List<RecyclerView> {
        if (root is RecyclerView) into.add(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectRecyclerViews(root.getChildAt(i), into)
            }
        }
        return into
    }

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
