package com.github.wrager.sbgscout.config

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
}
