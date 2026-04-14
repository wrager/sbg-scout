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
    fun `configureWebViewPerformance sets renderer priority to important`() {
        configureWebViewPerformance(webView)

        verify {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                true,
            )
        }
    }
}
