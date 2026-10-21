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
    private var startedUrl: String? = null
    private var sameDocumentFinishUrl: String? = null

    /** Прогресс начинает индикацию ещё до получения ответа основного документа. */
    fun onProgressChanged(progress: Int) {
        clearNextEventExpectations()
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
        clearNextEventExpectations()
        startedUrl = url
        loadingUrl = url
        lastLoadingProgress = 0
        onLoadingChanged(true)
    }

    fun onVisitedHistoryUpdated(url: String?) {
        val isStartedDocumentCommit = url == startedUrl
        clearNextEventExpectations()
        val currentUrl = loadingUrl
        if (isStartedDocumentCommit || url == null || currentUrl == null) return
        /*
         * doUpdateVisitedHistory нового документа приходит сразу после его onPageStarted,
         * а после навигации внутри документа с изменением только fragment WebView сразу
         * присылает onPageFinished, хотя ресурсы документа ещё грузятся. Поэтому оба
         * ожидания действуют только до следующего события.
         * 🤖 Generated with Claude Opus 5.5
         */
        if (url.substringBefore(FRAGMENT_SEPARATOR) == currentUrl.substringBefore(FRAGMENT_SEPARATOR)) {
            sameDocumentFinishUrl = url
        }
        loadingUrl = url
    }

    fun onPageFinished(url: String?) {
        val isSameDocumentFinish = url != null && url == sameDocumentFinishUrl
        clearNextEventExpectations()
        // После редиректа/нового перехода может прийти завершение предыдущего URL.
        // Процент прогресса здесь не подходит: Chromium достигает 100 до загрузки
        // низкоприоритетных изображений и iframe, ещё до настоящего onPageFinished.
        if (url == null || isSameDocumentFinish || url != loadingUrl) return
        loadingUrl = null
        onLoadingChanged(false)
    }

    private fun clearNextEventExpectations() {
        startedUrl = null
        sameDocumentFinishUrl = null
    }

    private companion object {
        const val COMPLETE_PROGRESS = 100
        const val FRAGMENT_SEPARATOR = '#'
    }
}
