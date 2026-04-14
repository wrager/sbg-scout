package com.github.wrager.sbgscout.config

import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.github.wrager.sbgscout.BuildConfig

/**
 * Централизованные URL игры и guard для страницы `/app`.
 *
 * В прод-сборках значения берутся из [BuildConfig]; в androidTest
 * используется runtime-override через [appUrlOverride]/[loginUrlOverride]/[hostMatchOverride],
 * который задаёт локальный fake-сервер (MockWebServer) с произвольным портом.
 */
object GameUrls {

    @VisibleForTesting
    @Volatile
    internal var appUrlOverride: String? = null

    @VisibleForTesting
    @Volatile
    internal var loginUrlOverride: String? = null

    @VisibleForTesting
    @Volatile
    internal var hostMatchOverride: String? = null

    val appUrl: String
        get() = appUrlOverride ?: BuildConfig.GAME_APP_URL

    val loginUrl: String
        get() = loginUrlOverride ?: BuildConfig.GAME_LOGIN_URL

    fun isGameAppPage(url: String?): Boolean {
        if (url == null) return false
        val host = hostMatchOverride ?: BuildConfig.GAME_HOST_MATCH
        return url.contains(host) && url.contains("/app")
    }

    /**
     * Строгая проверка принадлежности URL игровому хосту по `Uri.host`
     * (а не `contains`, чтобы `https://evil.com/?q=sbg-game.ru` не прошёл).
     *
     * Используется для валидации deep-link URL из внешнего `Intent.ACTION_VIEW`
     * перед передачей в `WebView.loadUrl`. Intent-filter в манифесте уже
     * ограничивает источники до `sbg-game.ru`, но компонент `exported`, и
     * приложение может получить intent с произвольным Uri напрямую — без
     * этой проверки мы загрузили бы чужой URL внутри игрового WebView.
     */
    fun isGameUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = hostMatchOverride ?: BuildConfig.GAME_HOST_MATCH
        return uri.host == host
    }
}
