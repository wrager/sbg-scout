package com.github.wrager.sbgscout

import android.view.View
import android.webkit.WebView
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class WebViewPerformanceTest {

    private val webView: WebView = mockk(relaxed = true)

    @Test
    fun `configureWebViewPerformance sets hardware layer type`() {
        configureWebViewPerformance(webView)

        verify { webView.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    }

    @Test
    fun `configureWebViewPerformance skips renderer priority below API 26`() {
        // В JVM unit-тестах Build.VERSION.SDK_INT = 0, поэтому ветка API 26+ пропускается.
        configureWebViewPerformance(webView)

        verify(exactly = 0) {
            webView.setRendererPriorityPolicy(any(), any())
        }
    }

    @Test
    fun `setImportantRendererPriority sets renderer priority to important`() {
        setImportantRendererPriority(webView)

        verify {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                true,
            )
        }
    }
}
