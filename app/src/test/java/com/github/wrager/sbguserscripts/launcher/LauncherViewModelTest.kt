package com.github.wrager.sbguserscripts.launcher

import com.github.wrager.sbguserscripts.script.injector.InjectionStateStorage
import com.github.wrager.sbguserscripts.script.model.ScriptHeader
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import com.github.wrager.sbguserscripts.script.preset.ConflictDetector
import com.github.wrager.sbguserscripts.script.preset.PresetScripts
import com.github.wrager.sbguserscripts.script.preset.StaticConflictRules
import com.github.wrager.sbguserscripts.script.storage.ScriptStorage
import com.github.wrager.sbguserscripts.script.updater.GithubAsset
import com.github.wrager.sbguserscripts.script.updater.GithubRelease
import com.github.wrager.sbguserscripts.script.updater.GithubReleaseProvider
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloadResult
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloader
import com.github.wrager.sbguserscripts.script.updater.ScriptUpdateChecker
import com.github.wrager.sbguserscripts.script.updater.ScriptUpdateResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var scriptStorage: ScriptStorage
    private val conflictDetector = ConflictDetector(StaticConflictRules())
    private lateinit var downloader: ScriptDownloader
    private lateinit var updateChecker: ScriptUpdateChecker
    private lateinit var githubReleaseProvider: GithubReleaseProvider
    private lateinit var injectionStateStorage: InjectionStateStorage

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scriptStorage = mockk()
        downloader = mockk()
        updateChecker = mockk()
        githubReleaseProvider = mockk()
        injectionStateStorage = mockk()

        coEvery { updateChecker.checkAllForUpdates() } returns emptyList()
        every { injectionStateStorage.getSnapshot() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads existing scripts from storage`() = runTest {
        every { scriptStorage.getAll() } returns listOf(testScript())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.scripts.any { it.name == "Test Script" && it.isDownloaded })
    }

    @Test
    fun `shows undownloaded presets in list`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(PresetScripts.ALL.size, state.scripts.size)
        assertTrue(state.scripts.all { !it.isDownloaded })
    }

    @Test
    fun `undownloaded preset has no download progress`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()

        val viewModel = createViewModel()
        advanceUntilIdle()

        val svpItem = viewModel.uiState.value.scripts.first { it.identifier == PresetScripts.SVP.identifier }
        assertFalse(svpItem.isDownloaded)
        assertNull(svpItem.downloadProgress)
    }

    @Test
    fun `downloadScript marks script as downloaded after completion`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        val svpScript = testScript(identifier = PresetScripts.SVP.identifier, name = "SVP")
        coEvery {
            downloader.download(PresetScripts.SVP.downloadUrl, isPreset = true, any())
        } answers {
            every { scriptStorage.getAll() } returns listOf(svpScript)
            ScriptDownloadResult.Success(svpScript)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.downloadScript(PresetScripts.SVP.identifier)
        advanceUntilIdle()

        val svpItem = viewModel.uiState.value.scripts.first { it.identifier == PresetScripts.SVP.identifier }
        assertTrue(svpItem.isDownloaded)
    }

    @Test
    fun `downloadScript shows progress during download`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        coEvery {
            downloader.download(PresetScripts.SVP.downloadUrl, isPreset = true, any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val onProgress = arg<((Int) -> Unit)?>(2)
            onProgress?.invoke(50)
            ScriptDownloadResult.Failure(PresetScripts.SVP.downloadUrl, RuntimeException("fail"))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.downloadScript(PresetScripts.SVP.identifier)
        advanceUntilIdle()

        val svpItem = viewModel.uiState.value.scripts.first { it.identifier == PresetScripts.SVP.identifier }
        assertNull(svpItem.downloadProgress)
    }

    @Test
    fun `checkUpdatesInBackground marks scripts as up to date`() = runTest {
        val script = testScript()
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpToDate(script.identifier),
        )

        val viewModel = createViewModel(autoUpdateEnabled = true)
        advanceUntilIdle()

        val item = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertTrue(item.isUpToDate)
        assertFalse(item.hasUpdateAvailable)
    }

    @Test
    fun `checkUpdatesInBackground marks scripts with update available`() = runTest {
        val script = testScript(version = "1.0.0")
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpdateAvailable(
                script.identifier,
                com.github.wrager.sbguserscripts.script.model.ScriptVersion("1.0.0"),
                com.github.wrager.sbguserscripts.script.model.ScriptVersion("2.0.0"),
            ),
        )

        val viewModel = createViewModel(autoUpdateEnabled = true)
        advanceUntilIdle()

        val item = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertTrue(item.hasUpdateAvailable)
        assertFalse(item.isUpToDate)
    }

    @Test
    fun `toggles script enabled state`() = runTest {
        val script = testScript()
        every { scriptStorage.getAll() } returns listOf(script)
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleScript(script.identifier, true)

        verify { scriptStorage.setEnabled(script.identifier, true) }
    }

    @Test
    fun `detects conflicts for enabled scripts`() = runTest {
        val svp = testScript(
            identifier = PresetScripts.SVP.identifier,
            name = "SVP",
            enabled = true,
        )
        val eui = testScript(
            identifier = PresetScripts.EUI.identifier,
            name = "EUI",
            enabled = true,
        )
        every { scriptStorage.getAll() } returns listOf(svp, eui)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val svpItem = state.scripts.first { it.identifier == PresetScripts.SVP.identifier }
        assertTrue(svpItem.conflictNames.contains("EUI"))

        val euiItem = state.scripts.first { it.identifier == PresetScripts.EUI.identifier }
        assertTrue(euiItem.conflictNames.contains("SVP"))
    }

    @Test
    fun `no conflicts for disabled scripts`() = runTest {
        val svp = testScript(
            identifier = PresetScripts.SVP.identifier,
            name = "SVP",
            enabled = false,
        )
        val eui = testScript(
            identifier = PresetScripts.EUI.identifier,
            name = "EUI",
            enabled = true,
        )
        every { scriptStorage.getAll() } returns listOf(svp, eui)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val svpItem = state.scripts.first { it.identifier == PresetScripts.SVP.identifier }
        assertTrue(svpItem.conflictNames.isEmpty())
    }

    @Test
    fun `addScript downloads and refreshes list`() = runTest {
        val newScript = testScript(name = "New Script")
        every { scriptStorage.getAll() } returns emptyList()
        coEvery { downloader.download("https://example.com/script.user.js", isPreset = false) } returns
            ScriptDownloadResult.Success(newScript)

        val viewModel = createViewModel()
        advanceUntilIdle()

        every { scriptStorage.getAll() } returns listOf(newScript)
        viewModel.addScript("https://example.com/script.user.js")
        advanceUntilIdle()

        coVerify { downloader.download("https://example.com/script.user.js", isPreset = false) }
    }

    @Test
    fun `deleteScript removes script and refreshes list`() = runTest {
        val script = testScript()
        val scriptList = mutableListOf(script)
        every { scriptStorage.getAll() } answers { scriptList.toList() }
        every { scriptStorage.delete(script.identifier) } answers { scriptList.clear() }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteScript(script.identifier)
        advanceUntilIdle()

        verify { scriptStorage.delete(script.identifier) }
        assertFalse(viewModel.uiState.value.scripts.any { it.identifier == script.identifier })
    }

    @Test
    fun `updateAllScripts downloads updates when available`() = runTest {
        val script = testScript(
            identifier = ScriptIdentifier("test/updatable"),
            version = "1.0.0",
        )
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpdateAvailable(
                script.identifier,
                com.github.wrager.sbguserscripts.script.model.ScriptVersion("1.0.0"),
                com.github.wrager.sbguserscripts.script.model.ScriptVersion("2.0.0"),
            ),
        )
        val updatedScript = testScript(
            identifier = ScriptIdentifier("test/updatable"),
            version = "2.0.0",
        )
        coEvery { downloader.download(any(), isPreset = false) } returns
            ScriptDownloadResult.Success(updatedScript)
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateAllScripts()
        advanceUntilIdle()

        coVerify { downloader.download(script.sourceUrl!!, isPreset = false) }
        verify { scriptStorage.setEnabled(updatedScript.identifier, script.enabled) }
    }

    @Test
    fun `loadVersions sends VersionsLoaded event for GitHub script`() = runTest {
        val script = testScript(
            sourceUrl = "https://github.com/owner/repo/releases/latest/download/script.user.js",
        )
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery {
            githubReleaseProvider.fetchReleases("owner", "repo")
        } returns listOf(
            GithubRelease(
                "v2.0.0",
                listOf(GithubAsset("script.user.js", "https://github.com/download/v2.0.0/script.user.js")),
            ),
            GithubRelease(
                "v1.0.0",
                listOf(GithubAsset("script.user.js", "https://github.com/download/v1.0.0/script.user.js")),
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<LauncherEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.loadVersions(script.identifier)
        advanceUntilIdle()

        val versionsEvent = events.filterIsInstance<LauncherEvent.VersionsLoaded>().first()
        assertEquals(2, versionsEvent.versions.size)
        assertEquals("v2.0.0", versionsEvent.versions[0].tagName)
        assertFalse(versionsEvent.versions[0].isCurrent)
        assertEquals("v1.0.0", versionsEvent.versions[1].tagName)
        assertTrue(versionsEvent.versions[1].isCurrent)

        job.cancel()
    }

    @Test
    fun `loadVersions filters releases without matching asset`() = runTest {
        val script = testScript(
            sourceUrl = "https://github.com/owner/repo/releases/latest/download/script.user.js",
        )
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery {
            githubReleaseProvider.fetchReleases("owner", "repo")
        } returns listOf(
            GithubRelease(
                "v2.0.0",
                listOf(GithubAsset("other.user.js", "https://github.com/download/v2.0.0/other.user.js")),
            ),
            GithubRelease(
                "v1.0.0",
                listOf(GithubAsset("script.user.js", "https://github.com/download/v1.0.0/script.user.js")),
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<LauncherEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.loadVersions(script.identifier)
        advanceUntilIdle()

        val versionsEvent = events.filterIsInstance<LauncherEvent.VersionsLoaded>().first()
        assertEquals(1, versionsEvent.versions.size)
        assertEquals("v1.0.0", versionsEvent.versions[0].tagName)

        job.cancel()
    }

    @Test
    fun `installVersion downloads and preserves enabled state`() = runTest {
        val script = testScript(enabled = true)
        every { scriptStorage.getAll() } returns listOf(script)
        val updatedScript = testScript(version = "2.0.0", enabled = false)
        coEvery {
            downloader.download("https://example.com/v2/script.user.js", isPreset = false)
        } returns ScriptDownloadResult.Success(updatedScript)
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.installVersion(script.identifier, "https://example.com/v2/script.user.js", isLatest = true)
        advanceUntilIdle()

        verify { scriptStorage.setEnabled(updatedScript.identifier, true) }
        val item = viewModel.uiState.value.scripts.first { it.identifier == updatedScript.identifier }
        assertTrue(item.isUpToDate)
    }

    @Test
    fun `updateScript sets reloadNeeded when game was previously loaded`() = runTest {
        val script = testScript(version = "1.0.0", enabled = true)
        val updatedScript = testScript(version = "2.0.0", enabled = false)
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { downloader.download(script.sourceUrl!!, isPreset = false) } answers {
            every { scriptStorage.getAll() } returns listOf(updatedScript)
            ScriptDownloadResult.Success(updatedScript)
        }
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        // Снимок не пуст — игра уже загружалась
        every { injectionStateStorage.getSnapshot() } returns setOf("test/script::1.0.0")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateScript(script.identifier)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.reloadNeeded)
    }

    @Test
    fun `updateScript does not set reloadNeeded when game was never loaded`() = runTest {
        val script = testScript(version = "1.0.0", enabled = true)
        val updatedScript = testScript(version = "2.0.0", enabled = false)
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { downloader.download(script.sourceUrl!!, isPreset = false) } answers {
            every { scriptStorage.getAll() } returns listOf(updatedScript)
            ScriptDownloadResult.Success(updatedScript)
        }
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        every { injectionStateStorage.getSnapshot() } returns null  // игра не загружалась

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateScript(script.identifier)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.reloadNeeded)
    }

    @Test
    fun `reinstallScript re-downloads from sourceUrl`() = runTest {
        val script = testScript(enabled = true)
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery {
            downloader.download(script.sourceUrl!!, isPreset = false)
        } returns ScriptDownloadResult.Success(script)
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.reinstallScript(script.identifier)
        advanceUntilIdle()

        coVerify { downloader.download(script.sourceUrl!!, isPreset = false) }
        verify { scriptStorage.setEnabled(script.identifier, true) }
    }

    private fun createViewModel(autoUpdateEnabled: Boolean = false) = LauncherViewModel(
        scriptStorage,
        conflictDetector,
        downloader,
        updateChecker,
        githubReleaseProvider,
        injectionStateStorage,
        autoUpdateEnabled,
    )

    private fun testScript(
        identifier: ScriptIdentifier = ScriptIdentifier("test/script"),
        name: String = "Test Script",
        version: String = "1.0.0",
        enabled: Boolean = false,
        sourceUrl: String = "https://example.com/script.user.js",
    ) = UserScript(
        identifier = identifier,
        header = ScriptHeader(name = name, version = version),
        sourceUrl = sourceUrl,
        updateUrl = "https://example.com/script.meta.js",
        content = "console.log('test')",
        enabled = enabled,
        isPreset = false,
    )
}
