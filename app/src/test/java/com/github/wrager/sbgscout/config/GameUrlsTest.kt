package com.github.wrager.sbgscout.config

import com.github.wrager.sbgscout.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [GameUrls] — покрывают обе ветки runtime-override
 * ([appUrlOverride]/[loginUrlOverride]/[hostMatchOverride] = null/non-null)
 * и четыре ветки в [GameUrls.isGameAppPage] (null URL, host match, /app match).
 */
class GameUrlsTest {

    @After
    fun clearOverrides() {
        GameUrls.appUrlOverride = null
        GameUrls.loginUrlOverride = null
        GameUrls.hostMatchOverride = null
        GameUrls.betaServerEnabled = false
    }

    @Test
    fun `appUrl falls back to BuildConfig when override is null`() {
        assertEquals(BuildConfig.GAME_APP_URL, GameUrls.appUrl)
    }

    @Test
    fun `appUrl returns override when set`() {
        GameUrls.appUrlOverride = "http://127.0.0.1:12345/app"
        assertEquals("http://127.0.0.1:12345/app", GameUrls.appUrl)
    }

    @Test
    fun `loginUrl falls back to BuildConfig when override is null`() {
        assertEquals(BuildConfig.GAME_LOGIN_URL, GameUrls.loginUrl)
    }

    @Test
    fun `loginUrl returns override when set`() {
        GameUrls.loginUrlOverride = "http://127.0.0.1:12345/login"
        assertEquals("http://127.0.0.1:12345/login", GameUrls.loginUrl)
    }

    @Test
    fun `appUrl returns beta url when betaServerEnabled is true`() {
        GameUrls.betaServerEnabled = true
        assertEquals("https://beta.sbg-game.ru/app", GameUrls.appUrl)
    }

    @Test
    fun `loginUrl returns beta url when betaServerEnabled is true`() {
        GameUrls.betaServerEnabled = true
        assertEquals("https://beta.sbg-game.ru/login", GameUrls.loginUrl)
    }

    @Test
    fun `appUrl override takes precedence over betaServerEnabled`() {
        GameUrls.betaServerEnabled = true
        GameUrls.appUrlOverride = "http://127.0.0.1:12345/app"
        assertEquals("http://127.0.0.1:12345/app", GameUrls.appUrl)
    }

    @Test
    fun `isGameAppPage returns false for null url`() {
        assertFalse(GameUrls.isGameAppPage(null))
    }

    @Test
    fun `isGameAppPage returns false when host does not match`() {
        // Default BuildConfig host = "sbg-game.ru", не совпадает с example.com
        assertFalse(GameUrls.isGameAppPage("https://example.com/app"))
    }

    @Test
    fun `isGameAppPage returns false when path does not contain app`() {
        GameUrls.hostMatchOverride = "127.0.0.1"
        assertFalse(GameUrls.isGameAppPage("http://127.0.0.1/login"))
    }

    @Test
    fun `isGameAppPage returns true when host matches and path contains app`() {
        GameUrls.hostMatchOverride = "127.0.0.1"
        assertTrue(GameUrls.isGameAppPage("http://127.0.0.1/app"))
    }

    @Test
    fun `isGameAppPage uses override host when set`() {
        GameUrls.hostMatchOverride = "fake.host"
        assertTrue(GameUrls.isGameAppPage("http://fake.host/app/profile"))
        assertFalse(GameUrls.isGameAppPage("http://sbg-game.ru/app"))
    }

    @Test
    fun `isGameAppPage uses BuildConfig host when override is null`() {
        // BuildConfig.GAME_HOST_MATCH в instr — "127.0.0.1"
        assertTrue(GameUrls.isGameAppPage("http://${BuildConfig.GAME_HOST_MATCH}/app"))
    }
}
