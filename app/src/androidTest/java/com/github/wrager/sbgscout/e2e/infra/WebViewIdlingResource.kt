package com.github.wrager.sbgscout.e2e.infra

import androidx.test.espresso.IdlingResource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IdlingResource, становящийся idle после первого вызова [markLoaded].
 *
 * Подписывается на [com.github.wrager.sbgscout.webview.SbgWebViewClient.onGamePageFinished]
 * через [bindTo]: когда игровая страница полностью загружена, Espresso снимает
 * блокировку ожидания и тест может дёргать `evaluateJavascript`.
 *
 * Один инстанс рассчитан на один тест. Если тесту нужно перезагрузить страницу
 * и ждать повторно — использовать [reset] между ожиданиями.
 */
class WebViewIdlingResource : IdlingResource {

    private val loaded = AtomicBoolean(false)
    private var callback: IdlingResource.ResourceCallback? = null

    override fun getName(): String = "WebViewIdlingResource"

    override fun isIdleNow(): Boolean = loaded.get()

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.callback = callback
    }

    fun markLoaded() {
        if (loaded.compareAndSet(false, true)) {
            callback?.onTransitionToIdle()
        }
    }

    fun reset() {
        loaded.set(false)
    }
}
