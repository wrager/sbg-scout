package com.github.wrager.sbgscout.launcher

import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.installer.ScriptInstallResult
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.ConflictDetector
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.preset.StaticConflictRules
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.updater.GithubAsset
import com.github.wrager.sbgscout.script.updater.GithubRelease
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.script.updater.ScriptDownloadResult
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.updater.ScriptUpdateChecker
import com.github.wrager.sbgscout.script.updater.ScriptUpdateResult
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

@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var scriptStorage: ScriptStorage
    private val conflictDetector = ConflictDetector(StaticConflictRules())
    private lateinit var downloader: ScriptDownloader
    private lateinit var scriptInstaller: ScriptInstaller
    private lateinit var updateChecker: ScriptUpdateChecker
    private lateinit var githubReleaseProvider: GithubReleaseProvider
    private lateinit var injectionStateStorage: InjectionStateStorage
    private lateinit var scriptProvisioner: DefaultScriptProvisioner

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scriptStorage = mockk()
        downloader = mockk()
        scriptInstaller = mockk()
        updateChecker = mockk()
        githubReleaseProvider = mockk()
        injectionStateStorage = mockk()
        scriptProvisioner = mockk()

        coEvery { updateChecker.checkAllForUpdates() } returns emptyList()
        every { injectionStateStorage.getSnapshot() } returns null
        every { scriptProvisioner.markProvisioned(any()) } just Runs
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
        assertNull(svpItem.operationState)
    }

    @Test
    fun `downloadScript marks script as downloaded after completion`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptStorage.setEnabled(any(), any()) } just Runs
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
        assertNull(svpItem.operationState)
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
    fun `loadVersions uses releaseTag to determine current version`() = runTest {
        // Скрипт установлен из тега v6.14.0, но @version в заголовке — 26.1.7
        // (например, CUI хостится в репо EUI)
        val script = testScript(
            version = "26.1.7",
            sourceUrl = "https://github.com/owner/repo/releases/latest/download/script.user.js",
            releaseTag = "v6.14.0",
        )
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery {
            githubReleaseProvider.fetchReleases("owner", "repo")
        } returns listOf(
            GithubRelease(
                "v6.15.0",
                listOf(GithubAsset("script.user.js", "https://github.com/download/v6.15.0/script.user.js")),
            ),
            GithubRelease(
                "v6.14.0",
                listOf(GithubAsset("script.user.js", "https://github.com/download/v6.14.0/script.user.js")),
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
        assertFalse(versionsEvent.versions[0].isCurrent)
        assertTrue(versionsEvent.versions[1].isCurrent)

        job.cancel()
    }

    @Test
    fun `installVersion downloads and preserves enabled state`() = runTest {
        val script = testScript(enabled = true)
        every { scriptStorage.getAll() } returns listOf(script)
        val updatedScript = testScript(version = "2.0.0", enabled = false)
        coEvery {
            downloader.download("https://example.com/v2/script.user.js", isPreset = false, any())
        } returns ScriptDownloadResult.Success(updatedScript)
        every { scriptStorage.save(any()) } answers {
            val saved = arg<UserScript>(0)
            every { scriptStorage.getAll() } returns listOf(saved)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.installVersion(
            script.identifier,
            "https://example.com/v2/script.user.js",
            isLatest = true,
            tagName = "v2.0.0",
        )
        advanceUntilIdle()

        verify {
            scriptStorage.save(match { it.enabled && it.releaseTag == "v2.0.0" })
        }
        val item = viewModel.uiState.value.scripts.first { it.identifier == updatedScript.identifier }
        assertEquals(ScriptOperationState.UpToDate, item.operationState)
    }

    @Test
    fun `updateScript sets reloadNeeded when game was previously loaded`() = runTest {
        val script = testScript(version = "1.0.0", enabled = true)
        val updatedScript = testScript(version = "2.0.0", enabled = false)
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { downloader.download(script.sourceUrl!!, isPreset = false, any()) } answers {
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
        coEvery { downloader.download(script.sourceUrl!!, isPreset = false, any()) } answers {
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
    fun `updateScript shows progress during download`() = runTest {
        val script = testScript(version = "1.0.0")
        every { scriptStorage.getAll() } returns listOf(script)
        val updatedScript = testScript(version = "2.0.0")
        coEvery { downloader.download(script.sourceUrl!!, isPreset = false, any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val onProgress = arg<((Int) -> Unit)?>(2)
            onProgress?.invoke(50)
            every { scriptStorage.getAll() } returns listOf(updatedScript)
            ScriptDownloadResult.Success(updatedScript)
        }
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateScript(script.identifier)
        advanceUntilIdle()

        // После завершения прогресс должен быть сброшен, состояние — UpToDate
        val item = viewModel.uiState.value.scripts.first { it.identifier == updatedScript.identifier }
        assertEquals(ScriptOperationState.UpToDate, item.operationState)
    }

    @Test
    fun `installVersion with isLatest false shows update available instead of up to date`() = runTest {
        val script = testScript(version = "2.0.0", enabled = true)
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpToDate(script.identifier),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Явная проверка помечает скрипт как upToDate
        viewModel.checkUpdates()
        advanceUntilIdle()

        val itemBefore = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertEquals(ScriptOperationState.UpToDate, itemBefore.operationState)

        val olderScript = testScript(version = "1.0.0", enabled = false)
        coEvery {
            downloader.download("https://example.com/v1/script.user.js", isPreset = false, any())
        } returns ScriptDownloadResult.Success(olderScript)
        every { scriptStorage.save(any()) } answers {
            val saved = arg<UserScript>(0)
            every { scriptStorage.getAll() } returns listOf(saved)
        }

        viewModel.installVersion(
            script.identifier,
            "https://example.com/v1/script.user.js",
            isLatest = false,
            tagName = "v1.0.0",
        )
        advanceUntilIdle()

        val itemAfter = viewModel.uiState.value.scripts.first { it.identifier == olderScript.identifier }
        assertEquals(ScriptOperationState.UpdateAvailable, itemAfter.operationState)
    }

    @Test
    fun `checkUpdates transitions from CheckingUpdate to UpToDate`() = runTest {
        val script = testScript()
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpToDate(script.identifier),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // До проверки — нет состояния операции
        assertNull(viewModel.uiState.value.scripts.first { it.identifier == script.identifier }.operationState)

        viewModel.checkUpdates()
        advanceUntilIdle()

        // После проверки — UpToDate
        val item = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertEquals(ScriptOperationState.UpToDate, item.operationState)
    }

    @Test
    fun `checkUpdates transitions from CheckingUpdate to UpdateAvailable`() = runTest {
        val script = testScript(version = "1.0.0")
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpdateAvailable(
                script.identifier,
                com.github.wrager.sbgscout.script.model.ScriptVersion("1.0.0"),
                com.github.wrager.sbgscout.script.model.ScriptVersion("2.0.0"),
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.checkUpdates()
        advanceUntilIdle()

        val item = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertEquals(ScriptOperationState.UpdateAvailable, item.operationState)
    }

    @Test
    fun `checkUpdates clears CheckingUpdate on failure`() = runTest {
        val script = testScript()
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery { updateChecker.checkAllForUpdates() } throws RuntimeException("network error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.checkUpdates()
        advanceUntilIdle()

        // После ошибки — состояние должно быть очищено
        val item = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertNull(item.operationState)
    }

    @Test
    fun `deleteScript clears operation state`() = runTest {
        val script = testScript()
        val scriptList = mutableListOf(script)
        every { scriptStorage.getAll() } answers { scriptList.toList() }
        every { scriptStorage.delete(script.identifier) } answers { scriptList.clear() }
        coEvery { updateChecker.checkAllForUpdates() } returns listOf(
            ScriptUpdateResult.UpToDate(script.identifier),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Проверка обновлений помечает скрипт как UpToDate
        viewModel.checkUpdates()
        advanceUntilIdle()

        val itemBefore = viewModel.uiState.value.scripts.first { it.identifier == script.identifier }
        assertEquals(ScriptOperationState.UpToDate, itemBefore.operationState)

        // Удаление должно очистить состояние операции
        viewModel.deleteScript(script.identifier)
        advanceUntilIdle()

        val deletedItem = viewModel.uiState.value.scripts.find { it.identifier == script.identifier }
        assertNull(deletedItem)
    }

    @Test
    fun `downloadScript enables script when preset has enabledByDefault`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        val svpScript = testScript(
            identifier = PresetScripts.SVP.identifier,
            name = "SVP",
            isPreset = true,
        )
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

        verify { scriptStorage.setEnabled(svpScript.identifier, true) }
    }

    @Test
    fun `downloadScript marks preset as provisioned on success`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        val svpScript = testScript(
            identifier = PresetScripts.SVP.identifier,
            name = "SVP",
            isPreset = true,
        )
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

        verify { scriptProvisioner.markProvisioned(PresetScripts.SVP.identifier) }
    }

    @Test
    fun `downloadScript does not enable script when preset has no enabledByDefault`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        val cuiScript = testScript(
            identifier = PresetScripts.CUI.identifier,
            name = "CUI",
            isPreset = true,
        )
        coEvery {
            downloader.download(PresetScripts.CUI.downloadUrl, isPreset = true, any())
        } answers {
            every { scriptStorage.getAll() } returns listOf(cuiScript)
            ScriptDownloadResult.Success(cuiScript)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.downloadScript(PresetScripts.CUI.identifier)
        advanceUntilIdle()

        verify(exactly = 0) { scriptStorage.setEnabled(any(), any()) }
    }

    @Test
    fun `reinstallScript re-downloads from sourceUrl`() = runTest {
        val script = testScript(enabled = true)
        every { scriptStorage.getAll() } returns listOf(script)
        coEvery {
            downloader.download(script.sourceUrl!!, isPreset = false, any())
        } returns ScriptDownloadResult.Success(script)
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.reinstallScript(script.identifier)
        advanceUntilIdle()

        coVerify { downloader.download(script.sourceUrl!!, isPreset = false, any()) }
        verify { scriptStorage.setEnabled(script.identifier, true) }
    }

    @Test
    fun `addScriptFromContent installs script from raw content`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        val parsedScript = testScript(name = "File Script")
        every { scriptInstaller.parse("script content") } returns
            ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } answers {
            every { scriptStorage.getAll() } returns listOf(parsedScript)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<LauncherEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.addScriptFromContent("script content")
        advanceUntilIdle()

        verify { scriptInstaller.save(parsedScript) }
        assertTrue(events.any { it is LauncherEvent.ScriptAdded })

        job.cancel()
    }

    @Test
    fun `addScriptFromContent sends failure event for invalid header`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptInstaller.parse("bad content") } returns ScriptInstallResult.InvalidHeader

        val viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<LauncherEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.addScriptFromContent("bad content")
        advanceUntilIdle()

        assertTrue(events.any { it is LauncherEvent.ScriptAddFailed })

        job.cancel()
    }

    @Test
    fun `addScriptFromContent detects preset and saves as preset`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        val parsedScript = testScript(
            identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
            name = "SBG Vanilla+",
            sourceUrl = null,
        )
        every { scriptInstaller.parse(any()) } returns ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } answers {
            val saved = arg<UserScript>(0)
            every { scriptStorage.getAll() } returns listOf(saved)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addScriptFromContent("script content")
        advanceUntilIdle()

        verify {
            scriptInstaller.save(
                match {
                    it.isPreset &&
                        it.sourceUrl == PresetScripts.SVP.downloadUrl &&
                        it.updateUrl == PresetScripts.SVP.updateUrl
                },
            )
        }
        verify { scriptStorage.setEnabled(any(), true) }
        verify { scriptProvisioner.markProvisioned(PresetScripts.SVP.identifier) }
    }

    @Test
    fun `addScriptFromContent detects preset by downloadUrl in header`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptStorage.setEnabled(any(), any()) } just Runs
        val parsedScript = testScript(
            identifier = ScriptIdentifier("custom/namespace/SVP"),
            name = "SVP",
            sourceUrl = PresetScripts.SVP.downloadUrl,
        ).let { script ->
            script.copy(header = script.header.copy(downloadUrl = PresetScripts.SVP.downloadUrl))
        }
        every { scriptInstaller.parse(any()) } returns ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } answers {
            val saved = arg<UserScript>(0)
            every { scriptStorage.getAll() } returns listOf(saved)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addScriptFromContent("script content")
        advanceUntilIdle()

        verify { scriptInstaller.save(match { it.isPreset }) }
        verify { scriptProvisioner.markProvisioned(PresetScripts.SVP.identifier) }
    }

    @Test
    fun `addScriptFromContent does not match preset by namespace alone`() = runTest {
        val existingSvp = testScript(
            identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
            name = "SBG Vanilla+",
            sourceUrl = PresetScripts.SVP.downloadUrl,
            isPreset = true,
            enabled = true,
        )
        every { scriptStorage.getAll() } returns listOf(existingSvp)

        // Скрипт с тем же namespace, но другим именем — не должен перезаписать SVP
        val debugScript = testScript(
            identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SVP Debug"),
            name = "SVP Debug",
            sourceUrl = null,
        )
        every { scriptInstaller.parse(any()) } returns ScriptInstallResult.Parsed(debugScript)
        every { scriptInstaller.save(any()) } answers {
            val saved = arg<UserScript>(0)
            every { scriptStorage.getAll() } returns listOf(existingSvp, saved)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addScriptFromContent("script content")
        advanceUntilIdle()

        // Старый SVP не должен быть удалён
        verify(exactly = 0) { scriptStorage.delete(existingSvp.identifier) }
        // Новый скрипт сохранён как пользовательский, не как пресет
        verify { scriptInstaller.save(match { !it.isPreset }) }
    }

    @Test
    fun `addScriptFromContent cleans up old preset entry when identifier changes`() = runTest {
        val oldSvp = testScript(
            identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/Old SVP Name"),
            name = "Old SVP Name",
            sourceUrl = PresetScripts.SVP.downloadUrl,
            isPreset = true,
            enabled = true,
        )
        every { scriptStorage.getAll() } returns listOf(oldSvp)
        every { scriptStorage.delete(any()) } just Runs
        every { scriptStorage.setEnabled(any(), any()) } just Runs

        val parsedScript = testScript(
            identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
            name = "SBG Vanilla+",
            sourceUrl = null,
        )
        every { scriptInstaller.parse(any()) } returns ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } answers {
            val saved = arg<UserScript>(0)
            every { scriptStorage.getAll() } returns listOf(saved)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addScriptFromContent("script content")
        advanceUntilIdle()

        verify { scriptStorage.delete(oldSvp.identifier) }
        verify { scriptInstaller.save(match { it.isPreset }) }
    }

    @Test
    fun `file-imported script appears in UI list`() = runTest {
        every { scriptStorage.getAll() } returns emptyList()
        val parsedScript = testScript(
            identifier = ScriptIdentifier("example.com/My Script"),
            name = "My Script",
        )
        every { scriptInstaller.parse("script content") } returns
            ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } answers {
            every { scriptStorage.getAll() } returns listOf(parsedScript)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addScriptFromContent("script content")
        advanceUntilIdle()

        val scripts = viewModel.uiState.value.scripts
        val customScript = scripts.find { it.identifier == parsedScript.identifier }
        assertTrue(
            "File-imported script should be in the UI list",
            customScript != null && customScript.isDownloaded,
        )
    }

    @Test
    fun `orphaned preset script appears in custom section`() = runTest {
        // Скрипт с isPreset=true, но sourceUrl не совпадает ни с одним пресетом
        val orphanedScript = testScript(
            identifier = ScriptIdentifier("github.com/someone/some-script/Script"),
            name = "Orphaned Preset Script",
            sourceUrl = "https://example.com/unmatched.user.js",
            isPreset = true,
        )
        every { scriptStorage.getAll() } returns listOf(orphanedScript)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val scripts = viewModel.uiState.value.scripts
        val found = scripts.find { it.identifier == orphanedScript.identifier }
        assertTrue(
            "Orphaned preset script should still be visible in the UI list",
            found != null && found.isDownloaded,
        )
    }

    private fun createViewModel() = LauncherViewModel(
        scriptStorage,
        conflictDetector,
        downloader,
        scriptInstaller,
        updateChecker,
        githubReleaseProvider,
        injectionStateStorage,
        scriptProvisioner,
    )

    private fun testScript(
        identifier: ScriptIdentifier = ScriptIdentifier("test/script"),
        name: String = "Test Script",
        version: String = "1.0.0",
        enabled: Boolean = false,
        sourceUrl: String? = "https://example.com/script.user.js",
        releaseTag: String? = null,
        isPreset: Boolean = false,
    ) = UserScript(
        identifier = identifier,
        header = ScriptHeader(name = name, version = version),
        sourceUrl = sourceUrl,
        updateUrl = "https://example.com/script.meta.js",
        content = "console.log('test')",
        enabled = enabled,
        isPreset = isPreset,
        releaseTag = releaseTag,
    )
}
