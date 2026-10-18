package com.github.wrager.sbgscout.script.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptTest {
    private val header = ScriptHeader(name = "Test Script", version = "1.0.0")
    private val identifier = ScriptIdentifier("test/script")

    @Test
    fun `default values are applied correctly`() {
        val script =
            UserScript(
                identifier = identifier,
                header = header,
                sourceUrl = "https://example.com/script.user.js",
                updateUrl = "https://example.com/script.meta.js",
                content = "console.log('test')",
            )

        assertFalse(script.enabled)
        assertFalse(script.isPreset)
    }

    @Test
    fun `data class equality works with same fields`() {
        val script1 =
            UserScript(
                identifier = identifier,
                header = header,
                sourceUrl = null,
                updateUrl = null,
                content = "content",
            )
        val script2 =
            UserScript(
                identifier = identifier,
                header = header,
                sourceUrl = null,
                updateUrl = null,
                content = "content",
            )

        assertEquals(script1, script2)
    }

    @Test
    fun `copy changes only specified fields`() {
        val original =
            UserScript(
                identifier = identifier,
                header = header,
                sourceUrl = "https://example.com",
                updateUrl = "https://example.com/meta",
                content = "content",
                enabled = false,
                isPreset = true,
            )

        val copy = original.copy(enabled = true)

        assertTrue(copy.enabled)
        assertTrue(copy.isPreset)
        assertEquals(original.identifier, copy.identifier)
        assertEquals(original.header, copy.header)
        assertEquals(original.content, copy.content)
    }
}
