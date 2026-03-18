package com.github.wrager.sbguserscripts.script.storage

import android.content.SharedPreferences
import com.github.wrager.sbguserscripts.script.model.ScriptHeader
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptStorageImplTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var fileStorage: ScriptFileStorage
    private lateinit var storage: ScriptStorageImpl

    private val testIdentifier = ScriptIdentifier("test/script")
    private val testScript = UserScript(
        identifier = testIdentifier,
        header = ScriptHeader(name = "Test Script", version = "1.0.0"),
        sourceUrl = "https://example.com/script.user.js",
        updateUrl = "https://example.com/script.meta.js",
        content = "console.log('test')",
        enabled = true,
        isPreset = false,
    )

    @Before
    fun setUp() {
        sharedPreferences = mockk()
        editor = mockk()
        fileStorage = mockk()

        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just Runs

        storage = ScriptStorageImpl(sharedPreferences, fileStorage)
    }

    @Test
    fun `save writes metadata and content`() {
        every { fileStorage.writeContent(testIdentifier, testScript.content) } just Runs

        storage.save(testScript)

        verify { editor.putString(eq(testIdentifier.value), any()) }
        verify { editor.apply() }
        verify { fileStorage.writeContent(testIdentifier, testScript.content) }
    }

    @Test
    fun `getAll returns saved scripts`() {
        val json = ScriptSerializer.toJson(testScript).toString()
        every { sharedPreferences.all } returns mapOf(testIdentifier.value to json)
        every { fileStorage.readContent(testIdentifier) } returns testScript.content

        val result = storage.getAll()

        assertEquals(1, result.size)
        assertEquals(testIdentifier, result[0].identifier)
        assertEquals("Test Script", result[0].header.name)
        assertEquals(testScript.content, result[0].content)
    }

    @Test
    fun `getAll returns empty list when no scripts saved`() {
        every { sharedPreferences.all } returns emptyMap()

        val result = storage.getAll()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `delete removes metadata and content`() {
        every { fileStorage.deleteContent(testIdentifier) } just Runs

        storage.delete(testIdentifier)

        verify { editor.remove(testIdentifier.value) }
        verify { editor.apply() }
        verify { fileStorage.deleteContent(testIdentifier) }
    }

    @Test
    fun `getEnabled returns only enabled scripts`() {
        val enabledScript = testScript.copy(enabled = true)
        val disabledScript = testScript.copy(
            identifier = ScriptIdentifier("test/disabled"),
            enabled = false,
        )
        val enabledJson = ScriptSerializer.toJson(enabledScript).toString()
        val disabledJson = ScriptSerializer.toJson(disabledScript).toString()

        every { sharedPreferences.all } returns mapOf(
            enabledScript.identifier.value to enabledJson,
            disabledScript.identifier.value to disabledJson,
        )
        every { fileStorage.readContent(enabledScript.identifier) } returns "content1"
        every { fileStorage.readContent(disabledScript.identifier) } returns "content2"

        val result = storage.getEnabled()

        assertEquals(1, result.size)
        assertTrue(result[0].enabled)
    }

    @Test
    fun `setEnabled updates enabled state`() {
        val json = ScriptSerializer.toJson(testScript).toString()
        every { sharedPreferences.getString(testIdentifier.value, null) } returns json

        storage.setEnabled(testIdentifier, false)

        verify { editor.putString(eq(testIdentifier.value), any()) }
        verify { editor.apply() }
    }

    @Test
    fun `setEnabled does nothing when script not found`() {
        every { sharedPreferences.getString(any(), null) } returns null

        storage.setEnabled(ScriptIdentifier("nonexistent"), true)

        verify(exactly = 0) { editor.putString(any(), any()) }
    }

    @Test
    fun `getAll skips scripts with corrupted JSON`() {
        val validJson = ScriptSerializer.toJson(testScript).toString()
        every { sharedPreferences.all } returns mapOf(
            "valid" to validJson,
            "corrupted" to "not valid json",
        )
        every { fileStorage.readContent(ScriptIdentifier("valid")) } returns "content"

        val result = storage.getAll()

        assertEquals(1, result.size)
    }

    @Test
    fun `getAll returns script with empty content when file missing`() {
        val json = ScriptSerializer.toJson(testScript).toString()
        every { sharedPreferences.all } returns mapOf(testIdentifier.value to json)
        every { fileStorage.readContent(testIdentifier) } returns null

        val result = storage.getAll()

        assertEquals(1, result.size)
        assertEquals("", result[0].content)
    }

    @Test
    fun `save overwrites existing script`() {
        val updatedScript = testScript.copy(
            header = ScriptHeader(name = "Updated Script", version = "2.0.0"),
        )
        every { fileStorage.writeContent(testIdentifier, updatedScript.content) } just Runs

        storage.save(updatedScript)

        verify { editor.putString(eq(testIdentifier.value), any()) }
        verify { fileStorage.writeContent(testIdentifier, updatedScript.content) }
    }

    @Test
    fun `getAll skips non-string entries`() {
        every { sharedPreferences.all } returns mapOf(
            "intValue" to 42,
            "boolValue" to true,
        )

        val result = storage.getAll()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `setEnabled preserves other fields`() {
        val json = ScriptSerializer.toJson(testScript).toString()
        every { sharedPreferences.getString(testIdentifier.value, null) } returns json

        val capturedJson = mutableListOf<String>()
        every { editor.putString(any(), capture(capturedJson)) } returns editor

        storage.setEnabled(testIdentifier, false)

        val updatedJson = org.json.JSONObject(capturedJson.first())
        assertFalse(updatedJson.getBoolean("enabled"))
        assertEquals(testIdentifier.value, updatedJson.getString("identifier"))
    }
}
