package com.github.wrager.sbgscout

import android.os.Build
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
    fun `configureWebViewPerformance sets renderer priority on API 26+`() {
        configureWebViewPerformance(webView, sdkVersion = Build.VERSION_CODES.O)

        verify {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                true,
            )
        }
    }

    @Test
    fun `configureWebViewPerformance skips renderer priority below API 26`() {
        configureWebViewPerformance(webView, sdkVersion = Build.VERSION_CODES.N_MR1)

        verify(exactly = 0) {
            webView.setRendererPriorityPolicy(any(), any())
        }
    }
}
