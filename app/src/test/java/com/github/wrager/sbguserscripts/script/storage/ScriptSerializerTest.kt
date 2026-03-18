package com.github.wrager.sbguserscripts.script.storage

import com.github.wrager.sbguserscripts.script.model.ScriptHeader
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSerializerTest {

    @Test
    fun `roundtrip serialization preserves all fields`() {
        val script = UserScript(
            identifier = ScriptIdentifier("test/script"),
            header = ScriptHeader(
                name = "Test Script",
                version = "1.0.0",
                description = "A test script",
                author = "tester",
                namespace = "https://example.com",
                match = listOf("https://sbg-game.ru/app/*"),
                grant = listOf("none"),
                downloadUrl = "https://example.com/script.user.js",
                updateUrl = "https://example.com/script.meta.js",
                runAt = "document-idle",
                iconUrl = "https://example.com/icon.png",
            ),
            sourceUrl = "https://example.com/script.user.js",
            updateUrl = "https://example.com/script.meta.js",
            content = "console.log('test')",
            enabled = true,
            isPreset = true,
        )

        val json = ScriptSerializer.toJson(script)
        val restored = ScriptSerializer.fromJson(json, script.content)

        assertEquals(script.identifier, restored.identifier)
        assertEquals(script.header.name, restored.header.name)
        assertEquals(script.header.version, restored.header.version)
        assertEquals(script.header.description, restored.header.description)
        assertEquals(script.header.author, restored.header.author)
        assertEquals(script.header.namespace, restored.header.namespace)
        assertEquals(script.header.match, restored.header.match)
        assertEquals(script.header.grant, restored.header.grant)
        assertEquals(script.header.downloadUrl, restored.header.downloadUrl)
        assertEquals(script.header.updateUrl, restored.header.updateUrl)
        assertEquals(script.header.runAt, restored.header.runAt)
        assertEquals(script.header.iconUrl, restored.header.iconUrl)
        assertEquals(script.sourceUrl, restored.sourceUrl)
        assertEquals(script.updateUrl, restored.updateUrl)
        assertEquals(script.content, restored.content)
        assertTrue(restored.enabled)
        assertTrue(restored.isPreset)
    }

    @Test
    fun `handles null optional fields`() {
        val script = UserScript(
            identifier = ScriptIdentifier("minimal/script"),
            header = ScriptHeader(name = "Minimal"),
            sourceUrl = null,
            updateUrl = null,
            content = "code",
        )

        val json = ScriptSerializer.toJson(script)
        val restored = ScriptSerializer.fromJson(json, script.content)

        assertEquals("Minimal", restored.header.name)
        assertNull(restored.header.version)
        assertNull(restored.header.description)
        assertNull(restored.sourceUrl)
        assertNull(restored.updateUrl)
        assertFalse(restored.enabled)
        assertFalse(restored.isPreset)
    }

    @Test
    fun `serializes multi-value fields as JSON arrays`() {
        val script = UserScript(
            identifier = ScriptIdentifier("multi/script"),
            header = ScriptHeader(
                name = "Multi Match",
                match = listOf("https://a.com/*", "https://b.com/*"),
                grant = listOf("GM_getValue", "GM_setValue"),
            ),
            sourceUrl = null,
            updateUrl = null,
            content = "code",
        )

        val json = ScriptSerializer.toJson(script)
        val headerJson = json.getJSONObject("header")
        val matchArray = headerJson.getJSONArray("match")

        assertEquals(2, matchArray.length())
        assertEquals("https://a.com/*", matchArray.getString(0))
        assertEquals("https://b.com/*", matchArray.getString(1))
    }

    @Test
    fun `deserializes missing optional fields as defaults`() {
        val json = JSONObject().apply {
            put("identifier", "test/id")
            put("header", JSONObject().apply { put("name", "Test") })
        }

        val restored = ScriptSerializer.fromJson(json, "content")

        assertNull(restored.header.version)
        assertEquals(emptyList<String>(), restored.header.match)
        assertEquals(emptyList<String>(), restored.header.grant)
        assertFalse(restored.enabled)
    }

    @Test
    fun `handles empty match and grant lists`() {
        val script = UserScript(
            identifier = ScriptIdentifier("empty/lists"),
            header = ScriptHeader(
                name = "Empty Lists",
                match = emptyList(),
                grant = emptyList(),
            ),
            sourceUrl = null,
            updateUrl = null,
            content = "code",
        )

        val json = ScriptSerializer.toJson(script)
        val restored = ScriptSerializer.fromJson(json, script.content)

        assertEquals(emptyList<String>(), restored.header.match)
        assertEquals(emptyList<String>(), restored.header.grant)
    }
}
