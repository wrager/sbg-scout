package com.github.wrager.sbgscout.script.installer

import android.content.SharedPreferences
import com.github.wrager.sbgscout.script.preset.PresetScripts
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
    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private var storedPinged: Set<String> = emptySet()
    private var storedBundledVersions: Set<String> = emptySet()
    private lateinit var beacon: BundledScriptBeacon

    private val svpIdentifier = PresetScripts.SVP.identifier.value
    private val svpKey081 = "$svpIdentifier:0.8.1"
    private val svpPinned081 =
        "https://github.com/wrager/sbg-vanilla-plus/releases/download/v0.8.1/sbg-vanilla-plus.user.js"
    private val svpPinned090 =
        "https://github.com/wrager/sbg-vanilla-plus/releases/download/v0.9.0/sbg-vanilla-plus.user.js"

    @Before
    fun setUp() {
        httpFetcher = mockk()
        preferences = mockk()
        editor = mockk()
        storedPinged = emptySet()
        storedBundledVersions = emptySet()

        every { preferences.getStringSet(BundledScriptBeacon.KEY_PINGED, emptySet()) } answers {
            storedPinged
        }
        every {
            preferences.getStringSet(BundledScriptInstaller.KEY_BUNDLED_VERSIONS, emptySet())
        } answers { storedBundledVersions }
        every { preferences.edit() } returns editor
        every { editor.putStringSet(BundledScriptBeacon.KEY_PINGED, any()) } answers {
            @Suppress("UNCHECKED_CAST")
            storedPinged = secondArg<Set<String>>()
            editor
        }
        every { editor.apply() } just Runs

        beacon = BundledScriptBeacon(httpFetcher, preferences)
    }

    @Test
    fun `pings bundled version on first run`() = runTest {
        storedBundledVersions = setOf(svpKey081)
        coEvery { httpFetcher.fetch(svpPinned081) } returns ""

        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        verify { editor.putStringSet(BundledScriptBeacon.KEY_PINGED, setOf(svpKey081)) }
        assertEquals(setOf(svpKey081), storedPinged)
    }

    @Test
    fun `does not ping same version twice`() = runTest {
        storedBundledVersions = setOf(svpKey081)
        storedPinged = setOf(svpKey081)

        beacon.ping()

        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
    }

    @Test
    fun `pings new bundled version after APK update`() = runTest {
        val svpKey090 = "$svpIdentifier:0.9.0"
        storedBundledVersions = setOf(svpKey090)
        storedPinged = setOf(svpKey081)
        coEvery { httpFetcher.fetch(svpPinned090) } returns ""

        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned090) }
        assertEquals(setOf(svpKey081, svpKey090), storedPinged)
    }

    @Test
    fun `does not ping version from storage after script update via dialog`() = runTest {
        // SVP обновлён пользователем через диалог (0.8.1 → 0.9.0), но
        // bundledVersions по-прежнему 0.8.1 (бандл в APK не менялся).
        // Beacon должен НЕ пинговать 0.9.0 — она уже скачана через
        // ScriptDownloader, counter инкрементился при реальном download.
        storedBundledVersions = setOf(svpKey081)
        storedPinged = setOf(svpKey081)

        beacon.ping()

        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
    }

    @Test
    fun `network error leaves state unchanged for retry on next start`() = runTest {
        storedBundledVersions = setOf(svpKey081)
        coEvery { httpFetcher.fetch(svpPinned081) } throws IOException("network")

        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
        assertEquals(emptySet<String>(), storedPinged)
    }

    @Test
    fun `skips preset with no bundled version recorded`() = runTest {
        // bundledVersions пуст — BundledScriptInstaller ещё не запускался
        // (или скрипт установлен не из бандла, а из сети).
        storedBundledVersions = emptySet()

        beacon.ping()

        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
        verify(exactly = 0) { editor.putStringSet(any(), any()) }
    }

    @Test
    fun `partial failure stores only successful pings`() = runTest {
        // Два бандлированных пресета: SVP пингуется успешно, EUI падает.
        val euiIdentifier = PresetScripts.EUI.identifier.value
        val euiKey = "$euiIdentifier:1.0.0"
        storedBundledVersions = setOf(svpKey081, euiKey)
        coEvery { httpFetcher.fetch(svpPinned081) } returns ""
        val euiPinnedUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/download/v1.0.0/eui.user.js"
        coEvery { httpFetcher.fetch(euiPinnedUrl) } throws IOException("network")

        beacon = BundledScriptBeacon(
            httpFetcher,
            preferences,
            bundledPresets = listOf(PresetScripts.SVP, PresetScripts.EUI),
        )
        beacon.ping()

        coVerify(exactly = 1) { httpFetcher.fetch(svpPinned081) }
        coVerify(exactly = 1) { httpFetcher.fetch(euiPinnedUrl) }
        // В state только успешный SVP — EUI должен ретраиться.
        assertEquals(setOf(svpKey081), storedPinged)
    }
}
