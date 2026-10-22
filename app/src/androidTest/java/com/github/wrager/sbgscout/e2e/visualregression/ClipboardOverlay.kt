package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue

/**
 * Панель буфера обмена SystemUI, которую может оставить restoreClipboard предыдущего теста.
 * Её текст зависит от содержимого clipboard и системной локали, поэтому снимок ждёт её
 * исчезновения по resource ID, а при timeout тест падает до capture.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
object ClipboardOverlay {
    private const val GONE_TIMEOUT_MS = 10_000L

    fun waitUntilGone() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val preview = By.res("com.android.systemui", "clipboard_preview")
        assertTrue(
            "Панель буфера обмена SystemUI не исчезла за ${GONE_TIMEOUT_MS}ms",
            device.wait(Until.gone(preview), GONE_TIMEOUT_MS),
        )
    }
}
