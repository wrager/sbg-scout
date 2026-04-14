package com.github.wrager.sbgscout.script.provisioner

import android.content.SharedPreferences
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.updater.ScriptDownloadResult
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultScriptProvisionerTest {

    private lateinit var scriptStorage: ScriptStorage
    private lateinit var downloader: ScriptDownloader
    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var provisioner: DefaultScriptProvisioner

    @Before
    fun setUp() {
        scriptStorage = mockk()
        downloader = mockk()
        preferences = mockk()
        editor = mockk()

        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns emptySet()
        every { preferences.edit() } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.apply() } just Runs
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        every { scriptStorage.getAll() } returns emptyList()

        provisioner = DefaultScriptProvisioner(scriptStorage, downloader, preferences)
    }

    @Test
    fun `downloads and enables scripts with enabledByDefault`() = runTest {
        val script = testScript(identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus"))
        coEvery { downloader.download(any(), isPreset = true) } returns
            ScriptDownloadResult.Success(script)

        val result = provisioner.provision()

        assertTrue(result)
        coVerify { downloader.download(any(), isPreset = true) }
        verify { scriptStorage.setEnabled(script.identifier, true) }
        verify { editor.putStringSet("provisioned_defaults", setOf("github.com/wrager/sbg-vanilla-plus")) }
    }

    @Test
    fun `skips already provisioned scripts`() = runTest {
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns
            setOf("github.com/wrager/sbg-vanilla-plus")

        provisioner.provision()

        coVerify(exactly = 0) { downloader.download(any(), any()) }
    }

    @Test
    fun `retries on next launch when download fails`() = runTest {
        coEvery { downloader.download(any(), isPreset = true) } returns
            ScriptDownloadResult.Failure("url", RuntimeException("network error"))

        val result = provisioner.provision()

        assertFalse(result)
        // Не добавлять в provisioned_defaults при ошибке
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
        verify(exactly = 0) { scriptStorage.setEnabled(any(), any()) }
    }

    @Test
    fun `does not download presets without enabledByDefault`() = runTest {
        // CUI и EUI не имеют enabledByDefault=true
        // provision() вызывается, но downloader не вызывается для них
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns
            setOf("github.com/wrager/sbg-vanilla-plus")

        provisioner.provision()

        coVerify(exactly = 0) { downloader.download(any(), any()) }
    }

    @Test
    fun `skips scripts already present in storage`() = runTest {
        val installedScript = testScript(
            identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
            sourceUrl = "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
        )
        every { scriptStorage.getAll() } returns listOf(installedScript)

        val result = provisioner.provision()

        assertTrue(result)
        coVerify(exactly = 0) { downloader.download(any(), any()) }
        verify(exactly = 0) { scriptStorage.setEnabled(any(), any()) }
    }

    @Test
    fun `calls onScriptLoading with display name before each download`() = runTest {
        val script = testScript(identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus"))
        coEvery { downloader.download(any(), isPreset = true) } returns
            ScriptDownloadResult.Success(script)

        val reportedNames = mutableListOf<String>()
        provisioner.provision(onScriptLoading = { name -> reportedNames.add(name) })

        assertEquals(1, reportedNames.size)
        assertTrue(reportedNames.first().isNotEmpty())
    }

    @Test
    fun `markProvisioned prevents re-provisioning after script deletion`() {
        provisioner.markProvisioned(
            com.github.wrager.sbgscout.script.preset.PresetScripts.ALL.first { it.enabledByDefault }.identifier,
        )

        verify { editor.putStringSet("provisioned_defaults", setOf("github.com/wrager/sbg-vanilla-plus")) }
    }

    @Test
    fun `hasPendingScripts returns true when no presets provisioned`() {
        // Покрывает ветку isNotEmpty() = true в hasPendingScripts().
        assertTrue(provisioner.hasPendingScripts())
    }

    @Test
    fun `hasPendingScripts returns false when all enabled presets are provisioned`() {
        // Покрывает ветку isNotEmpty() = false в hasPendingScripts().
        // Только SVP имеет enabledByDefault=true — помечаем его как provisioned.
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns
            setOf("github.com/wrager/sbg-vanilla-plus")

        assertFalse(provisioner.hasPendingScripts())
    }

    @Test
    fun `isProvisioned returns true for identifier in provisioned set`() {
        // Покрывает основную ветку isProvisioned = true.
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns
            setOf("github.com/wrager/sbg-vanilla-plus")

        assertTrue(provisioner.isProvisioned(ScriptIdentifier("github.com/wrager/sbg-vanilla-plus")))
    }

    @Test
    fun `isProvisioned returns false for identifier not in provisioned set`() {
        // Покрывает ветку isProvisioned = false.
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns
            setOf("other/preset")

        assertFalse(provisioner.isProvisioned(ScriptIdentifier("github.com/wrager/sbg-vanilla-plus")))
    }

    @Test
    fun `provision treats null getStringSet as empty set`() = runTest {
        // SharedPreferences API допускает null из getStringSet, `?: emptySet()`
        // защитный elvis — тесты должны покрыть null-ветку.
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns null
        val script = testScript(identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus"))
        coEvery { downloader.download(any(), isPreset = true) } returns
            ScriptDownloadResult.Success(script)

        val result = provisioner.provision()

        assertTrue(result)
        coVerify { downloader.download(any(), isPreset = true) }
    }

    @Test
    fun `isProvisioned treats null getStringSet as empty set`() {
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns null

        assertFalse(provisioner.isProvisioned(ScriptIdentifier("github.com/wrager/sbg-vanilla-plus")))
    }

    @Test
    fun `markProvisioned treats null current set as empty`() {
        every { preferences.getStringSet("provisioned_defaults", emptySet()) } returns null

        provisioner.markProvisioned(ScriptIdentifier("some/preset"))

        verify { editor.putStringSet("provisioned_defaults", setOf("some/preset")) }
    }

    private fun testScript(
        identifier: ScriptIdentifier = ScriptIdentifier("test/script"),
        sourceUrl: String = "https://example.com/script.user.js",
    ) = UserScript(
        identifier = identifier,
        header = ScriptHeader(name = "Test Script", version = "1.0.0"),
        sourceUrl = sourceUrl,
        updateUrl = "https://example.com/script.meta.js",
        content = "console.log('test')",
        enabled = false,
        isPreset = true,
    )
}
