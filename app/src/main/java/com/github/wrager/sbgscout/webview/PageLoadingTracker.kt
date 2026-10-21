package com.github.wrager.sbgscout.webview

/**
 * Сообщает, грузится ли основной документ WebView вместе с его load-blocking
 * ресурсами и фреймами. Получает события `WebViewClient` и `WebChromeClient`.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
class PageLoadingTracker(
    private val onLoadingChanged: (Boolean) -> Unit,
) {
    private var loadingUrl: String? = null
    private var lastLoadingProgress = COMPLETE_PROGRESS

    /** Прогресс начинает индикацию ещё до получения ответа основного документа. */
    fun onProgressChanged(progress: Int) {
        if (progress < COMPLETE_PROGRESS) {
            // Снижение прогресса - новая навигация; старый документ ещё может
            // прислать onPageFinished, пока новый ждёт ответа сервера.
            if (progress < lastLoadingProgress) loadingUrl = null
            onLoadingChanged(true)
        } else if (loadingUrl == null) {
            // Отмена/ошибка до onPageStarted: документа с ресурсами ещё нет.
            onLoadingChanged(false)
        }
        lastLoadingProgress = progress
    }

    fun onPageStarted(url: String?) {
        loadingUrl = url
        lastLoadingProgress = 0
        onLoadingChanged(true)
    }

    fun onPageFinished(
        url: String?,
        currentUrl: String?,
        originalUrl: String?,
    ) {
        // После редиректа/нового перехода может прийти завершение предыдущего URL.
        // Процент прогресса здесь не подходит: Chromium достигает 100 до загрузки
        // низкоприоритетных изображений и iframe, ещё до настоящего onPageFinished.
        if (url != null && isCurrentNavigation(url, currentUrl, originalUrl)) {
            loadingUrl = null
            onLoadingChanged(false)
        }
    }

    private fun isCurrentNavigation(
        url: String,
        currentUrl: String?,
        originalUrl: String?,
    ): Boolean {
        if (url == loadingUrl) return true
        // URL мог измениться в цепочке HTTP-редиректов без нового onPageStarted.
        return currentUrl == url && originalUrl == loadingUrl
    }

    private companion object {
        const val COMPLETE_PROGRESS = 100
    }
}
