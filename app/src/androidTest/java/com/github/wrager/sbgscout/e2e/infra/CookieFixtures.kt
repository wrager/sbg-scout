package com.github.wrager.sbgscout.e2e.infra

import android.webkit.CookieManager

/**
 * Выставляет fake-сессионные cookie на базовый URL fake-сервера.
 *
 * Реальные имена session-cookie игры неизвестны из refs/game/har/ (HAR
 * перехватил только кастомный заголовок `hittoken`, Set-Cookie не попали).
 * Для fake-окружения это не важно: dispatcher fake-сервера валидирует те же
 * имена, что мы здесь выставляем. При уточнении реальных имён — обновить
 * эту константу в одном месте.
 */
object CookieFixtures {

    const val FAKE_SESSION_TOKEN = "fake_e2e_token"

    fun injectFakeAuth(baseUrl: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setCookie(baseUrl, "hittoken=$FAKE_SESSION_TOKEN; Path=/")
        cookieManager.setCookie(baseUrl, "PHPSESSID=e2e_session; Path=/")
        cookieManager.flush()
    }

    fun clearAll() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }
}
