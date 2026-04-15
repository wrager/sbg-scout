package com.github.wrager.sbgscout.script.installer

import android.content.SharedPreferences
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.updater.HttpFetcher
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

class BundledScriptBeaconTest {

    private lateinit var httpFetcher: HttpFetcher
    private lateinit var scriptStorage: ScriptStorage
    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private var storedPinged: Set<String> = emptySet()
    private lateinit var beacon: BundledScriptBeacon

    private val svpKey081 =
        "${PresetScripts.SVP.identifier.value}:0.8.1"
    private val svpPinned081 =
        "https://github.com/wrager/sbg-vanilla-plus/releases/download/v0.8.1/sbg-vanilla-plus.user.js"
    private val svpPinned090 =
        "https://github.com/wrager/sbg-vanilla-plus/releases/download/v0.9.0/sbg-vanilla-plus.user.js"

    @Before
    fun setUp() {
        httpFetcher = mockk()
        scriptStorage = mockk()
        preferences = mockk()
        editor = mockk()
        storedPinged = emptySet()

        every { preferences.getStringSet(BundledScriptBeacon.KEY_PINGED, emptySet()) } answers {
            storedPinged
        }
        every { preferences.edit() } returns editor
        every { editor.putStringSet(BundledScriptBeacon.KEY_PINGED, any()) } answers {
            @Suppress("UNCHECKED_CAST")
            storedPinged = secondArg<Set<String>>()
            editor
        }
        every { editor.apply() } just Runs

        beacon = BundledScriptBeacon(httpFetcher, scriptStorage, preferences)
    }

    @Test
    fun `pings bundled preset on first run`() = runTest {
        every { scriptStorage.getAll() } returns listOf(svpScript("0.8.1"))
        coEvery { httpFetcher.fetch(svpPinned081) } returns ""

        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        verify {
            editor.putStringSet(BundledScriptBeacon.KEY_PINGED, setOf(svpKey081))
        }
        assertEquals(setOf(svpKey081), storedPinged)
    }

    @Test
    fun `does not ping same version twice`() = runTest {
        storedPinged = setOf(svpKey081)
        every { scriptStorage.getAll() } returns listOf(svpScript("0.8.1"))

        beacon.ping()

        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
    }

    @Test
    fun `pings new version after bundle update`() = runTest {
        storedPinged = setOf(svpKey081)
        every { scriptStorage.getAll() } returns listOf(svpScript("0.9.0"))
        coEvery { httpFetcher.fetch(svpPinned090) } returns ""

        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned090) }
        val svpKey090 = "${PresetScripts.SVP.identifier.value}:0.9.0"
        assertEquals(setOf(svpKey081, svpKey090), storedPinged)
    }

    @Test
    fun `network error leaves state unchanged for retry on next start`() = runTest {
        every { scriptStorage.getAll() } returns listOf(svpScript("0.8.1"))
        coEvery { httpFetcher.fetch(svpPinned081) } throws IOException("network")

        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
        assertEquals(emptySet<String>(), storedPinged)
    }

    @Test
    fun `skips preset that is not installed in storage`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()

        beacon.ping()

        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
    }

    @Test
    fun `skips preset whose installed script has no version`() = runTest {
        every { scriptStorage.getAll() } returns listOf(svpScript(null))

        beacon.ping()

        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
    }

    @Test
    fun `strips v prefix from version before building pinned url`() = runTest {
        every { scriptStorage.getAll() } returns listOf(svpScript("v0.8.1"))
        coEvery { httpFetcher.fetch(svpPinned081) } returns ""

        beacon.ping()

        // Именно v0.8.1 в URL — одна v, не две.
        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        assertEquals(setOf(svpKey081), storedPinged)
    }

    @Test
    fun `partial failure stores only successful pings`() = runTest {
        // Два бандлированных пресета: один пингуется успешно, второй падает.
        // Успешный должен попасть в state, failed — нет.
        val secondPreset = PresetScripts.EUI
        val euiScript = UserScript(
            identifier = secondPreset.identifier,
            header = ScriptHeader(name = secondPreset.displayName, version = "1.0.0"),
            sourceUrl = secondPreset.downloadUrl,
            updateUrl = secondPreset.updateUrl,
            content = "",
            enabled = true,
        )
        every { scriptStorage.getAll() } returns listOf(svpScript("0.8.1"), euiScript)
        coEvery { httpFetcher.fetch(svpPinned081) } returns ""
        val euiPinnedUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/download/v1.0.0/eui.user.js"
        coEvery { httpFetcher.fetch(euiPinnedUrl) } throws IOException("network")

        beacon = BundledScriptBeacon(
            httpFetcher,
            scriptStorage,
            preferences,
            bundledPresets = listOf(PresetScripts.SVP, secondPreset),
        )
        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        coVerify(exactly = 1) { httpFetcher.fetch(euiPinnedUrl) }
        // В state только успешный SVP — EUI должен ретраиться на следующем старте.
        assertEquals(setOf(svpKey081), storedPinged)
    }

    private fun svpScript(version: String?): UserScript = UserScript(
        identifier = PresetScripts.SVP.identifier,
        header = ScriptHeader(name = "SBG Vanilla+", version = version),
        sourceUrl = PresetScripts.SVP.downloadUrl,
        updateUrl = PresetScripts.SVP.updateUrl,
        content = "",
        enabled = true,
    )
}
