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
 * Unit-тесты строгой валидации [GameUrls.isGameUrl] — используется для
 * проверки URL из `ACTION_VIEW`-intent'а перед передачей в `WebView.loadUrl`.
 *
 * `android.net.Uri` на чистой JVM — stub (все методы возвращают null),
 * поэтому `Uri.parse` мокается через MockK — тот же подход, что в
 * [com.github.wrager.sbgscout.bridge.ShareBridgeTest].
 */
class GameUrlsIsGameUrlTest {

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        GameUrls.hostMatchOverride = null
    }

    private fun stubUri(url: String, scheme: String?, host: String?) {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { Uri.parse(url) } returns uri
    }

    @Test
    fun `returns false for null`() {
        assertFalse(GameUrls.isGameUrl(null))
    }

    @Test
    fun `returns false for empty string`() {
        assertFalse(GameUrls.isGameUrl(""))
    }

    @Test
    fun `returns true for https url on game host`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri("https://sbg-game.ru/app", scheme = "https", host = "sbg-game.ru")
        assertTrue(GameUrls.isGameUrl("https://sbg-game.ru/app"))
    }

    @Test
    fun `returns true for http url on game host`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri("http://sbg-game.ru/app", scheme = "http", host = "sbg-game.ru")
        assertTrue(GameUrls.isGameUrl("http://sbg-game.ru/app"))
    }

    @Test
    fun `returns true for deep link with query parameters`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri(
            "https://sbg-game.ru/app?pt=abc123",
            scheme = "https",
            host = "sbg-game.ru",
        )
        assertTrue(GameUrls.isGameUrl("https://sbg-game.ru/app?pt=abc123"))
    }

    @Test
    fun `returns true for login path on game host`() {
        // Весь домен sbg-game.ru — под нашим приложением, не только /app.
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri("https://sbg-game.ru/login", scheme = "https", host = "sbg-game.ru")
        assertTrue(GameUrls.isGameUrl("https://sbg-game.ru/login"))
    }

    @Test
    fun `returns false when host does not match even if string contains game host`() {
        // Главное отличие от isGameAppPage: строгая проверка по Uri.host,
        // а не подстрочный contains — защита от `evil.com/?q=sbg-game.ru`.
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri(
            "https://evil.com/?q=sbg-game.ru",
            scheme = "https",
            host = "evil.com",
        )
        assertFalse(GameUrls.isGameUrl("https://evil.com/?q=sbg-game.ru"))
    }

    @Test
    fun `returns false for non http scheme`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri("ftp://sbg-game.ru/file", scheme = "ftp", host = "sbg-game.ru")
        assertFalse(GameUrls.isGameUrl("ftp://sbg-game.ru/file"))
    }

    @Test
    fun `returns false when scheme is null`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri("sbg-game.ru/app", scheme = null, host = null)
        assertFalse(GameUrls.isGameUrl("sbg-game.ru/app"))
    }

    @Test
    fun `returns false when Uri parse throws`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        every { Uri.parse("::::") } throws IllegalArgumentException("bad uri")
        assertFalse(GameUrls.isGameUrl("::::"))
    }

    @Test
    fun `uses host match override for instr builds`() {
        GameUrls.hostMatchOverride = "127.0.0.1"
        stubUri("http://127.0.0.1:8080/app", scheme = "http", host = "127.0.0.1")
        assertTrue(GameUrls.isGameUrl("http://127.0.0.1:8080/app"))
    }

    @Test
    fun `scheme check is case insensitive`() {
        GameUrls.hostMatchOverride = "sbg-game.ru"
        stubUri("HTTPS://sbg-game.ru/app", scheme = "HTTPS", host = "sbg-game.ru")
        assertTrue(GameUrls.isGameUrl("HTTPS://sbg-game.ru/app"))
    }
}
