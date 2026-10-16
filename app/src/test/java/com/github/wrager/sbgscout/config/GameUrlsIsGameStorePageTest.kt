package com.github.wrager.sbgscout.config

import android.net.Uri
import com.github.wrager.sbgscout.BuildConfig
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
 * Unit-тесты [GameUrls.isGameStorePage] — по нему `SbgWebViewClient` открывает
 * магазин игры во внешнем браузере.
 *
 * `android.net.Uri` на чистой JVM — stub (все методы возвращают null),
 * поэтому `Uri.parse` мокается через MockK, как в [GameUrlsIsGameUrlTest].
 */
class GameUrlsIsGameStorePageTest {

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        GameUrls.hostMatchOverride = GAME_HOST
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        GameUrls.appUrlOverride = null
        GameUrls.loginUrlOverride = null
        GameUrls.hostMatchOverride = null
        GameUrls.betaServerEnabled = false
    }

    private fun stubUri(url: String, scheme: String?, host: String?, path: String? = null) {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.path } returns path
        every { Uri.parse(url) } returns uri
    }

    @Test
    fun `returns true for store root with trailing slash`() {
        stubUri("https://$GAME_HOST/store/", scheme = "https", host = GAME_HOST, path = "/store/")
        assertTrue(GameUrls.isGameStorePage("https://$GAME_HOST/store/"))
    }

    @Test
    fun `returns true for store root without trailing slash`() {
        stubUri("https://$GAME_HOST/store", scheme = "https", host = GAME_HOST, path = "/store")
        assertTrue(GameUrls.isGameStorePage("https://$GAME_HOST/store"))
    }

    @Test
    fun `returns true for nested store page`() {
        stubUri(
            "https://$GAME_HOST/store/item/5?ref=game",
            scheme = "https",
            host = GAME_HOST,
            path = "/store/item/5",
        )
        assertTrue(GameUrls.isGameStorePage("https://$GAME_HOST/store/item/5?ref=game"))
    }

    @Test
    fun `returns true for http scheme`() {
        stubUri("http://$GAME_HOST/store/", scheme = "http", host = GAME_HOST, path = "/store/")
        assertTrue(GameUrls.isGameStorePage("http://$GAME_HOST/store/"))
    }

    @Test
    fun `scheme check is case insensitive`() {
        stubUri("HTTPS://$GAME_HOST/store/", scheme = "HTTPS", host = GAME_HOST, path = "/store/")
        assertTrue(GameUrls.isGameStorePage("HTTPS://$GAME_HOST/store/"))
    }

    @Test
    fun `returns false for path that only starts with store`() {
        // Граница сегмента пути: /storefront — другая страница, не магазин.
        stubUri("https://$GAME_HOST/storefront", scheme = "https", host = GAME_HOST, path = "/storefront")
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST/storefront"))
    }

    @Test
    fun `returns false for path shorter than store`() {
        stubUri("https://$GAME_HOST/stor", scheme = "https", host = GAME_HOST, path = "/stor")
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST/stor"))
    }

    @Test
    fun `returns false for other game pages`() {
        stubUri("https://$GAME_HOST/app", scheme = "https", host = GAME_HOST, path = "/app")
        stubUri("https://$GAME_HOST/", scheme = "https", host = GAME_HOST, path = "/")
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST/app"))
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST/"))
    }

    @Test
    fun `returns false for store path nested under other path`() {
        stubUri(
            "https://$GAME_HOST/login/store/",
            scheme = "https",
            host = GAME_HOST,
            path = "/login/store/",
        )
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST/login/store/"))
    }

    @Test
    fun `returns false when path is null`() {
        stubUri("https://$GAME_HOST", scheme = "https", host = GAME_HOST, path = null)
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST"))
    }

    @Test
    fun `returns false for store path on foreign host`() {
        stubUri("https://evil.com/store/", scheme = "https", host = "evil.com", path = "/store/")
        assertFalse(GameUrls.isGameStorePage("https://evil.com/store/"))
    }

    @Test
    fun `returns false for subdomain of game host`() {
        // Хост сравнивается целиком: поддомен игры магазином не считается,
        // если это не текущий хост из appUrl.
        stubUri(
            "https://shop.$GAME_HOST/store/",
            scheme = "https",
            host = "shop.$GAME_HOST",
            path = "/store/",
        )
        assertFalse(GameUrls.isGameStorePage("https://shop.$GAME_HOST/store/"))
    }

    @Test
    fun `returns false when game host is only in query`() {
        stubUri(
            "https://evil.com/store/?q=$GAME_HOST",
            scheme = "https",
            host = "evil.com",
            path = "/store/",
        )
        assertFalse(GameUrls.isGameStorePage("https://evil.com/store/?q=$GAME_HOST"))
    }

    @Test
    fun `returns false for non http scheme on game host`() {
        stubUri("ftp://$GAME_HOST/store/", scheme = "ftp", host = GAME_HOST, path = "/store/")
        assertFalse(GameUrls.isGameStorePage("ftp://$GAME_HOST/store/"))
    }

    @Test
    fun `returns false when scheme is null`() {
        stubUri("$GAME_HOST/store/", scheme = null, host = null, path = "$GAME_HOST/store/")
        assertFalse(GameUrls.isGameStorePage("$GAME_HOST/store/"))
    }

    @Test
    fun `returns false for null`() {
        assertFalse(GameUrls.isGameStorePage(null))
    }

    @Test
    fun `returns false for empty string`() {
        assertFalse(GameUrls.isGameStorePage(""))
    }

    @Test
    fun `returns false when Uri parse throws`() {
        every { Uri.parse("::::") } throws IllegalArgumentException("bad uri")
        assertFalse(GameUrls.isGameStorePage("::::"))
    }

    @Test
    fun `returns false when Uri parse returns null`() {
        // Незамоканный Uri.parse на JVM возвращает null (isReturnDefaultValues).
        every { Uri.parse("https://$GAME_HOST/store/") } returns null
        assertFalse(GameUrls.isGameStorePage("https://$GAME_HOST/store/"))
    }

    @Test
    fun `accepts host of appUrl override`() {
        // Хост из appUrl (в e2e — fake-сервер) игровой, даже если отличается от hostMatch.
        GameUrls.appUrlOverride = "http://fake.server:8080/app"
        stubUri("http://fake.server:8080/app", scheme = "http", host = "fake.server", path = "/app")
        stubUri("http://fake.server:8080/store/", scheme = "http", host = "fake.server", path = "/store/")
        assertTrue(GameUrls.isGameStorePage("http://fake.server:8080/store/"))
    }

    @Test
    fun `accepts beta server host when beta server is enabled`() {
        GameUrls.betaServerEnabled = true
        stubUri("https://$BETA_HOST/app", scheme = "https", host = BETA_HOST, path = "/app")
        stubUri("https://$BETA_HOST/store/", scheme = "https", host = BETA_HOST, path = "/store/")
        assertTrue(GameUrls.isGameStorePage("https://$BETA_HOST/store/"))
        // Основной хост при этом тоже остаётся игровым.
        stubUri("https://$GAME_HOST/store/", scheme = "https", host = GAME_HOST, path = "/store/")
        assertTrue(GameUrls.isGameStorePage("https://$GAME_HOST/store/"))
    }

    @Test
    fun `rejects beta server host when beta server is disabled`() {
        stubUri("https://$BETA_HOST/store/", scheme = "https", host = BETA_HOST, path = "/store/")
        assertFalse(GameUrls.isGameStorePage("https://$BETA_HOST/store/"))
    }

    @Test
    fun `uses BuildConfig host when override is null`() {
        GameUrls.hostMatchOverride = null
        val host = BuildConfig.GAME_HOST_MATCH
        stubUri("http://$host:8080/store/", scheme = "http", host = host, path = "/store/")
        assertTrue(GameUrls.isGameStorePage("http://$host:8080/store/"))
    }

    private companion object {
        const val GAME_HOST = "sbg-game.ru"
        const val BETA_HOST = "beta.sbg-game.ru"
    }
}
