package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ScriptUpdateCheckerTest {

    private lateinit var httpFetcher: HttpFetcher
    private lateinit var scriptStorage: ScriptStorage
    private lateinit var checker: ScriptUpdateChecker

    private val testIdentifier = ScriptIdentifier("test/script")

    @Before
    fun setUp() {
        httpFetcher = mockk()
        scriptStorage = mockk()
        checker = ScriptUpdateChecker(httpFetcher, scriptStorage)
    }

    private fun createScript(
        version: String? = "1.0.0",
        updateUrl: String? = "https://example.com/script.meta.js",
    ): UserScript = UserScript(
        identifier = testIdentifier,
        header = ScriptHeader(name = "Test Script", version = version),
        sourceUrl = "https://example.com/script.user.js",
        updateUrl = updateUrl,
        content = "code",
        enabled = true,
    )

    @Test
    fun `returns UpdateAvailable when remote version is newer`() = runTest {
        val script = createScript(version = "1.0.0")
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        val updateResult = result as ScriptUpdateResult.UpdateAvailable
        assertEquals(ScriptVersion("1.0.0"), updateResult.currentVersion)
        assertEquals(ScriptVersion("2.0.0"), updateResult.latestVersion)
    }

    @Test
    fun `returns UpToDate when versions are equal`() = runTest {
        val script = createScript(version = "1.0.0")
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_1

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
    }

    @Test
    fun `returns UpToDate when current version is newer`() = runTest {
        val script = createScript(version = "3.0.0")
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
    }

    @Test
    fun `returns CheckFailed when no updateUrl configured`() = runTest {
        val script = createScript(updateUrl = null)

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when network error occurs`() = runTest {
        val script = createScript()
        coEvery { httpFetcher.fetch(any()) } throws IOException("Network error")

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when remote header cannot be parsed`() = runTest {
        val script = createScript()
        coEvery { httpFetcher.fetch(any()) } returns "not a valid header"

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when current script has no version`() = runTest {
        val script = createScript(version = null)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when remote script has no version`() = runTest {
        val script = createScript()
        coEvery { httpFetcher.fetch(any()) } returns META_NO_VERSION

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `checkAllForUpdates checks all scripts from storage`() = runTest {
        val script1 = createScript(version = "1.0.0")
        val script2 = createScript(version = "2.0.0").copy(
            identifier = ScriptIdentifier("test/script2"),
        )
        coEvery { scriptStorage.getAll() } returns listOf(script1, script2)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val results = checker.checkAllForUpdates()

        assertEquals(2, results.size)
    }

    @Test
    fun `checkAllForUpdates filters out scripts with null updateUrl`() = runTest {
        // Покрывает ветку `.filter { it.updateUrl != null }` = false —
        // скрипт без updateUrl (sideloaded из файла) не должен попасть в
        // результат, иначе checker дёрнет null URL и упадёт.
        val scriptWithUpdate = createScript(version = "1.0.0")
        val scriptWithoutUpdate = createScript(version = "1.0.0", updateUrl = null).copy(
            identifier = ScriptIdentifier("test/no-update"),
        )
        coEvery { scriptStorage.getAll() } returns listOf(scriptWithUpdate, scriptWithoutUpdate)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_1

        val results = checker.checkAllForUpdates()

        assertEquals("Без-updateUrl скрипт должен быть отфильтрован", 1, results.size)
        assertEquals(testIdentifier, (results.single() as ScriptUpdateResult.UpToDate).identifier)
    }

    companion object {
        private val META_VERSION_1 = """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // ==/UserScript==
        """.trimIndent()

        private val META_VERSION_2 = """
            // ==UserScript==
            // @name Test Script
            // @version 2.0.0
            // ==/UserScript==
        """.trimIndent()

        private val META_NO_VERSION = """
            // ==UserScript==
            // @name Test Script
            // ==/UserScript==
        """.trimIndent()
    }
}
