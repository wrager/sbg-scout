package com.github.wrager.sbgscout.e2e.screenshots

import android.app.LocaleManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.LocaleList
import android.webkit.WebView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Маркер тестов, генерирующих README-скриншоты.
 *
 * Gradle task [updateReadmeScreenshots][README.md] фильтрует прогон по
 * этой аннотации (`-e annotation`), чтобы при ручной перегенерации не гонять
 * остальные e2e-сценарии.
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
 * Gradle task `updateReadmeScreenshots` далее переносит их в
 * `.github/images/screenshots/`.
 *
 * Низкоуровневые операции (snapshot экрана, scroll-stitch) живут в
 * [ScrollStitchCapture] - переиспользуются visual-regression-тестами без
 * масштабирования.
 */
object ReadmeScreenshotCapture {
    /**
     * Стандартная ширина PNG - 360 px (mdpi-эквивалент).
     * game_loading.png сохраняется в исходном разрешении, а в README
     * отображается с шириной 360 px ради чёткости мелкого текста.
     *
     * 🤖 Generated with GPT-6 Sol
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
     * Эмулятор для нашего CI — API 33 (см. docs/agent-setup.md), `LocaleManager`
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

    fun captureRegion(
        name: String,
        region: Rect,
        nativeResolution: Boolean = false,
    ) {
        val full = ScrollStitchCapture.takeScreenshot()
        try {
            val safeRegion = clamp(region, full.width, full.height)
            val cropped =
                Bitmap.createBitmap(
                    full,
                    safeRegion.left,
                    safeRegion.top,
                    safeRegion.width(),
                    safeRegion.height(),
                )
            try {
                writePng(name, if (nativeResolution) cropped else scaleToTargetWidth(cropped))
            } finally {
                cropped.recycle()
            }
        } finally {
            full.recycle()
        }
    }

    /**
     * Возвращает экранные координаты (физические пиксели) прямоугольника,
     * вычисленного через произвольный JS-сценарий [scriptReturningRectJson].
     * Скрипт обязан вернуть JSON со строковыми полями `left`, `top`, `width`,
     * `height` (в CSS-пикселях относительно viewport) либо `{"error": "..."}`.
     *
     * Реализация: CSS-координаты умножаем на density (= window.devicePixelRatio
     * в Android WebView при viewport meta `width=device-width`) и прибавляем
     * экранную позицию WebView, полученную из `View.getLocationOnScreen` —
     * она уже в физических пикселях (то же координатное пространство, что
     * у `UiAutomation.takeScreenshot()`).
     */
    fun webBoundsInScreen(
        webView: WebView,
        scriptReturningRectJson: String,
    ): Rect {
        val raw = evaluateJavascript(webView, scriptReturningRectJson)
        // evaluateJavascript возвращает результат как JSON-литерал (если скрипт
        // вернул строку - это `"\"{...}\""`, т.е. экранированный JSON внутри
        // строки). JSONTokener.nextValue корректно парсит любой JSON-литерал
        // (string, object, number, boolean) и обрабатывает все escape-
        // последовательности, в т.ч. \n, \t, \uXXXX.
        val tokenized = JSONTokener(raw).nextValue()
        val obj =
            when (tokenized) {
                is String -> JSONObject(tokenized)
                is JSONObject -> tokenized
                else -> error("webBoundsInScreen: unexpected JSON type ${tokenized::class.simpleName}: $raw")
            }
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
     * Снимает экран целиком, включая прокручиваемый за viewport контент
     * [scrollable], и пишет PNG масштабированный до [TARGET_WIDTH_PX].
     * Stitching-логика — в [ScrollStitchCapture.captureScrollableStitched].
     */
    fun captureFullScreenWithScroll(
        name: String,
        scrollable: RecyclerView,
    ) {
        val stitched = ScrollStitchCapture.captureScrollableStitched(scrollable)
        try {
            writePng(name, scaleToTargetWidth(stitched))
        } finally {
            stitched.recycle()
        }
    }

    private fun writePng(
        name: String,
        bitmap: Bitmap,
    ) {
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

    private fun clamp(
        region: Rect,
        maxWidth: Int,
        maxHeight: Int,
    ): Rect {
        val left = region.left.coerceIn(0, maxWidth - 1)
        val top = region.top.coerceIn(0, maxHeight - 1)
        val right = region.right.coerceIn(left + 1, maxWidth)
        val bottom = region.bottom.coerceIn(top + 1, maxHeight)
        return Rect(left, top, right, bottom)
    }

    private fun evaluateJavascript(
        webView: WebView,
        script: String,
    ): String {
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
}
