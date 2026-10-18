package com.github.wrager.sbgscout.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSettingsReaderTest {
    private val reader = GameSettingsReader()

    @Test
    fun `parse returns AUTO theme for auto value`() {
        val result = reader.parse("""{"theme": "auto", "lang": "sys"}""")
        assertEquals(GameSettingsReader.ThemeMode.AUTO, result?.theme)
    }

    @Test
    fun `parse returns DARK theme for dark value`() {
        val result = reader.parse("""{"theme": "dark", "lang": "sys"}""")
        assertEquals(GameSettingsReader.ThemeMode.DARK, result?.theme)
    }

    @Test
    fun `parse returns LIGHT theme for light value`() {
        val result = reader.parse("""{"theme": "light", "lang": "sys"}""")
        assertEquals(GameSettingsReader.ThemeMode.LIGHT, result?.theme)
    }

    @Test
    fun `parse returns AUTO for unknown theme value`() {
        val result = reader.parse("""{"theme": "unknown", "lang": "sys"}""")
        assertEquals(GameSettingsReader.ThemeMode.AUTO, result?.theme)
    }

    @Test
    fun `parse returns sys language`() {
        val result = reader.parse("""{"theme": "auto", "lang": "sys"}""")
        assertEquals("sys", result?.language)
    }

    @Test
    fun `parse returns specific language code`() {
        val result = reader.parse("""{"theme": "auto", "lang": "ru"}""")
        assertEquals("ru", result?.language)
    }

    @Test
    fun `parse returns defaults when fields are missing`() {
        val result = reader.parse("""{}""")
        assertEquals(GameSettingsReader.ThemeMode.AUTO, result?.theme)
        assertEquals("sys", result?.language)
    }

    @Test
    fun `parse returns null for null input`() {
        assertNull(reader.parse(null))
    }

    @Test
    fun `parse returns null for string null`() {
        assertNull(reader.parse("null"))
    }

    @Test
    fun `parse returns null for empty string`() {
        assertNull(reader.parse(""))
    }

    @Test
    fun `parse returns null for blank string`() {
        assertNull(reader.parse("   "))
    }

    @Test
    fun `parse returns null for invalid json`() {
        assertNull(reader.parse("not json"))
    }

    @Test
    fun `parse handles full game settings json`() {
        val json =
            """
            {"lang": "en", "theme": "dark", "imghid": false, "dsvhid": false,
             "arabic": false, "selfpos": false, "exref": false, "base": "cdb",
             "plrhid": false, "opacity": 2, "efmode": "full", "atkord": false}
            """.trimIndent()
        val result = reader.parse(json)
        assertEquals(GameSettingsReader.ThemeMode.DARK, result?.theme)
        assertEquals("en", result?.language)
    }
}
