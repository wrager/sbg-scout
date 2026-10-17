package com.github.wrager.sbgscout.config

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-тесты [GameUrls.isGameLoginPage] — по нему GameActivity решает,
 * оставить ли нативную «Scout» на странице вне игры.
 *
 * `android.net.Uri` на чистой JVM — stub, поэтому `Uri.parse` мокается
 * через MockK, как в [GameUrlsIsGameUrlTest]. Мокаются и проверяемый URL,
 * и [GameUrls.appUrl]: хост из него тоже считается игровым (бета-сервер).
 */
class GameUrlsIsGameLoginPageTest {

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        GameUrls.hostMatchOverride = GAME_HOST
        GameUrls.appUrlOverride = APP_URL
        stubUri(APP_URL, scheme = "https", host = GAME_HOST, path = "/app")
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        GameUrls.appUrlOverride = null
        GameUrls.loginUrlOverride = null
        GameUrls.hostMatchOverride = null
        GameUrls.betaServerEnabled = false
    }

    private fun stubUri(url: String, scheme: String?, host: String?, path: String?) {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.path } returns path
        every { Uri.parse(url) } returns uri
    }

    @Test
    fun `returns false for null`() {
        assertFalse(GameUrls.isGameLoginPage(null))
    }

    @Test
    fun `returns false for empty string`() {
        assertFalse(GameUrls.isGameLoginPage(""))
    }

    @Test
    fun `returns true for login path without trailing slash`() {
        stubUri("https://sbg-game.ru/login", scheme = "https", host = GAME_HOST, path = "/login")
        assertTrue(GameUrls.isGameLoginPage("https://sbg-game.ru/login"))
    }

    @Test
    fun `returns true for login path with trailing slash`() {
        stubUri("https://sbg-game.ru/login/", scheme = "https", host = GAME_HOST, path = "/login/")
        assertTrue(GameUrls.isGameLoginPage("https://sbg-game.ru/login/"))
    }

    @Test
    fun `returns true for login path with query`() {
        val url = "https://sbg-game.ru/login/?return_url=%2Fapp"
        stubUri(url, scheme = "https", host = GAME_HOST, path = "/login/")
        assertTrue(GameUrls.isGameLoginPage(url))
    }

    @Test
    fun `returns true for http login page`() {
        stubUri("http://sbg-game.ru/login", scheme = "http", host = GAME_HOST, path = "/login")
        assertTrue(GameUrls.isGameLoginPage("http://sbg-game.ru/login"))
    }

    @Test
    fun `scheme check is case insensitive`() {
        stubUri("HTTPS://sbg-game.ru/login", scheme = "HTTPS", host = GAME_HOST, path = "/login")
        assertTrue(GameUrls.isGameLoginPage("HTTPS://sbg-game.ru/login"))
    }

    @Test
    fun `returns false for path that only starts with login`() {
        stubUri("https://sbg-game.ru/loginx", scheme = "https", host = GAME_HOST, path = "/loginx")
        assertFalse(GameUrls.isGameLoginPage("https://sbg-game.ru/loginx"))
    }

    @Test
    fun `returns false for other page on game host`() {
        stubUri("https://sbg-game.ru/rules/game/", scheme = "https", host = GAME_HOST, path = "/rules/game/")
        assertFalse(GameUrls.isGameLoginPage("https://sbg-game.ru/rules/game/"))
    }

    @Test
    fun `returns false for game page`() {
        assertFalse(GameUrls.isGameLoginPage(APP_URL))
    }

    @Test
    fun `returns false for login path on foreign host`() {
        stubUri("https://evil.com/login", scheme = "https", host = "evil.com", path = "/login")
        assertFalse(GameUrls.isGameLoginPage("https://evil.com/login"))
    }

    @Test
    fun `returns false for non http scheme`() {
        stubUri("ftp://sbg-game.ru/login", scheme = "ftp", host = GAME_HOST, path = "/login")
        assertFalse(GameUrls.isGameLoginPage("ftp://sbg-game.ru/login"))
    }

    @Test
    fun `returns false when scheme is null`() {
        stubUri("sbg-game.ru/login", scheme = null, host = null, path = "sbg-game.ru/login")
        assertFalse(GameUrls.isGameLoginPage("sbg-game.ru/login"))
    }

    @Test
    fun `returns false when path is null`() {
        stubUri("https://sbg-game.ru", scheme = "https", host = GAME_HOST, path = null)
        assertFalse(GameUrls.isGameLoginPage("https://sbg-game.ru"))
    }

    @Test
    fun `returns false when url host is null and appUrl host is unknown`() {
        // Хост без значения не должен совпасть с неизвестным хостом appUrl.
        stubUri(APP_URL, scheme = "https", host = null, path = "/app")
        stubUri("https:///login", scheme = "https", host = null, path = "/login")
        assertFalse(GameUrls.isGameLoginPage("https:///login"))
    }

    @Test
    fun `returns false when Uri parse throws`() {
        every { Uri.parse("::::") } throws IllegalArgumentException("bad uri")
        assertFalse(GameUrls.isGameLoginPage("::::"))
    }

    @Test
    fun `returns true for login page on beta server host`() {
        // Хост бета-сервера берётся из appUrl: GAME_HOST_MATCH его не покрывает.
        GameUrls.appUrlOverride = null
        GameUrls.betaServerEnabled = true
        stubUri(BETA_APP_URL, scheme = "https", host = BETA_HOST, path = "/app")
        stubUri("https://beta.sbg-game.ru/login/", scheme = "https", host = BETA_HOST, path = "/login/")
        assertTrue(GameUrls.isGameLoginPage("https://beta.sbg-game.ru/login/"))
    }

    @Test
    fun `uses host match override for instr builds`() {
        GameUrls.hostMatchOverride = "127.0.0.1"
        stubUri("http://127.0.0.1:8080/login", scheme = "http", host = "127.0.0.1", path = "/login")
        assertTrue(GameUrls.isGameLoginPage("http://127.0.0.1:8080/login"))
    }

    private companion object {
        const val GAME_HOST = "sbg-game.ru"
        const val APP_URL = "https://sbg-game.ru/app"
        const val BETA_HOST = "beta.sbg-game.ru"
        const val BETA_APP_URL = "https://beta.sbg-game.ru/app"
    }
}
