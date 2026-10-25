package com.github.wrager.sbgscout.e2e.screenshots

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotCaptureTest {
    @Test
    fun reductionByThree_preservesVerticalLinesAcrossPixelPhases() {
        val brightness = (0..2).map { phase -> assertFineLinesSurvive(vertical = true, phase = phase) }
        assertTrue(
            "Контраст вертикальных штрихов зависит от фазы: $brightness",
            brightness.max() - brightness.min() <= 32,
        )
    }

    @Test
    fun reductionByThree_preservesHorizontalLinesAcrossPixelPhases() {
        val brightness = (0..2).map { phase -> assertFineLinesSurvive(vertical = false, phase = phase) }
        assertTrue(
            "Контраст горизонтальных штрихов зависит от фазы: $brightness",
            brightness.max() - brightness.min() <= 32,
        )
    }

    @Test
    fun fractionalReduction_preservesAspectRatioAndSolidColor() {
        for ((width, expectedHeight) in listOf(900 to 24, 721 to 29)) {
            val source = Bitmap.createBitmap(width, 60, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.rgb(80, 140, 200))
            val scaled = ReadmeScreenshotCapture.scaleToTargetWidth(source)
            try {
                assertEquals(360, scaled.width)
                assertEquals(expectedHeight, scaled.height)
                assertEquals(source.getPixel(0, 0), scaled.getPixel(180, 12))
                assertFalse(source.isRecycled)
            } finally {
                scaled.recycle()
                source.recycle()
            }
        }
    }

    @Test
    fun targetWidth_preservesOriginalPixels() {
        val source = Bitmap.createBitmap(360, 24, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(80, 140, 200))
        try {
            assertSame(source, ReadmeScreenshotCapture.scaleToTargetWidth(source))
        } finally {
            source.recycle()
        }
    }

    @Test
    fun captureProfile_acceptsPixel4AtDefaultFontScale() {
        ReadmeScreenshotCapture.requireCaptureProfile(1080, 2280, 440, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun captureProfile_rejectsDifferentScreenSize() {
        ReadmeScreenshotCapture.requireCaptureProfile(1080, 1920, 440, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun captureProfile_rejectsDifferentDensity() {
        ReadmeScreenshotCapture.requireCaptureProfile(1080, 2280, 480, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun captureProfile_rejectsDifferentFontScale() {
        ReadmeScreenshotCapture.requireCaptureProfile(1080, 2280, 440, 1.2f)
    }

    private fun assertFineLinesSurvive(
        vertical: Boolean,
        phase: Int,
    ): Int {
        val source = Bitmap.createBitmap(1080, 18, Bitmap.Config.ARGB_8888)
        val pixels =
            IntArray(source.width * source.height) { index ->
                val coordinate = if (vertical) index % source.width else index / source.width
                if (coordinate % 3 == phase) Color.BLACK else Color.WHITE
            }
        source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val scaled = ReadmeScreenshotCapture.scaleToTargetWidth(source)
        try {
            assertEquals(360, scaled.width)
            assertEquals(6, scaled.height)
            val brightness = Color.red(scaled.getPixel(180, 3))
            assertTrue(
                "Тонкие линии потеряны: vertical=$vertical, phase=$phase, brightness=$brightness",
                brightness in 32..240,
            )
            return brightness
        } finally {
            scaled.recycle()
            source.recycle()
        }
    }
}
