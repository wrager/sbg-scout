package com.github.wrager.sbgscout.script.installer

import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptInstallerTest {

    private lateinit var scriptStorage: ScriptStorage
    private lateinit var installer: ScriptInstaller

    @Before
    fun setUp() {
        scriptStorage = mockk()
        installer = ScriptInstaller(scriptStorage)
    }

    @Test
    fun `parse returns Parsed with correct script for valid header`() {
        val result = installer.parse(SCRIPT_WITH_HEADER)

        assertTrue(result is ScriptInstallResult.Parsed)
        val script = (result as ScriptInstallResult.Parsed).script
        assertEquals("Test Script", script.header.name)
        assertEquals("1.0.0", script.header.version)
        assertEquals(SCRIPT_WITH_HEADER, script.content)
    }

    @Test
    fun `parse returns InvalidHeader when no header found`() {
        val result = installer.parse("console.log('no header')")

        assertTrue(result is ScriptInstallResult.InvalidHeader)
    }

    @Test
    fun `parse builds identifier from namespace and name`() {
        val result = installer.parse(SCRIPT_WITH_NAMESPACE)

        assertTrue(result is ScriptInstallResult.Parsed)
        assertEquals(
            "github.com/test/repo/Test Script",
            (result as ScriptInstallResult.Parsed).script.identifier.value,
        )
    }

    @Test
    fun `parse uses name as identifier when no namespace`() {
        val result = installer.parse(SCRIPT_WITH_HEADER)

        assertTrue(result is ScriptInstallResult.Parsed)
        assertEquals(
            "Test Script",
            (result as ScriptInstallResult.Parsed).script.identifier.value,
        )
    }

    @Test
    fun `parse keeps namespace as-is when it has no http or https prefix`() {
        // Покрывает ветку `removePrefix("http://")` = не удалил (prefix отсутствует).
        val content = """
            // ==UserScript==
            // @name Test Script
            // @namespace e2e-custom-namespace
            // @version 1.0.0
            // ==/UserScript==
        """.trimIndent()

        val result = installer.parse(content) as ScriptInstallResult.Parsed
        assertEquals("e2e-custom-namespace/Test Script", result.script.identifier.value)
    }

    @Test
    fun `parse strips http prefix from namespace`() {
        val content = """
            // ==UserScript==
            // @name Test Script
            // @namespace http://example.com/scripts
            // @version 1.0.0
            // ==/UserScript==
        """.trimIndent()

        val result = installer.parse(content) as ScriptInstallResult.Parsed
        assertEquals("example.com/scripts/Test Script", result.script.identifier.value)
    }

    @Test
    fun `parse sets sourceUrl from header downloadUrl`() {
        val result = installer.parse(SCRIPT_WITH_DOWNLOAD_URL) as ScriptInstallResult.Parsed

        assertEquals("https://example.com/download.user.js", result.script.sourceUrl)
    }

    @Test
    fun `parse sets sourceUrl to null when header has no downloadUrl`() {
        val result = installer.parse(SCRIPT_WITH_HEADER) as ScriptInstallResult.Parsed

        assertNull(result.script.sourceUrl)
    }

    @Test
    fun `parse sets updateUrl from header updateUrl`() {
        val content = """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // @updateURL https://example.com/update.meta.js
            // @downloadURL https://example.com/download.user.js
            // ==/UserScript==
        """.trimIndent()

        val result = installer.parse(content) as ScriptInstallResult.Parsed
        assertEquals("https://example.com/update.meta.js", result.script.updateUrl)
    }

    @Test
    fun `parse falls back updateUrl to downloadUrl when no updateUrl`() {
        val result = installer.parse(SCRIPT_WITH_DOWNLOAD_URL) as ScriptInstallResult.Parsed

        assertEquals("https://example.com/download.user.js", result.script.updateUrl)
    }

    @Test
    fun `parse sets updateUrl to null when no urls in header`() {
        val result = installer.parse(SCRIPT_WITH_HEADER) as ScriptInstallResult.Parsed

        assertNull(result.script.updateUrl)
    }

    @Test
    fun `parse returns disabled non-preset script by default`() {
        val result = installer.parse(SCRIPT_WITH_HEADER) as ScriptInstallResult.Parsed

        assertFalse(result.script.enabled)
        assertFalse(result.script.isPreset)
    }

    @Test
    fun `save delegates to script storage`() {
        val script = mockk<UserScript>()
        every { scriptStorage.save(any()) } just Runs

        installer.save(script)

        val savedScript = slot<UserScript>()
        verify { scriptStorage.save(capture(savedScript)) }
        assertEquals(script, savedScript.captured)
    }

    companion object {
        private val SCRIPT_WITH_HEADER = """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // ==/UserScript==
            console.log('test');
        """.trimIndent()

        private val SCRIPT_WITH_NAMESPACE = """
            // ==UserScript==
            // @name Test Script
            // @namespace https://github.com/test/repo
            // @version 1.0.0
            // ==/UserScript==
        """.trimIndent()

        private val SCRIPT_WITH_DOWNLOAD_URL = """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // @downloadURL https://example.com/download.user.js
            // ==/UserScript==
        """.trimIndent()
    }
}
