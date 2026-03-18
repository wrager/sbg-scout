package com.github.wrager.sbguserscripts.launcher

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import com.github.wrager.sbguserscripts.script.preset.ConflictDetector
import com.github.wrager.sbguserscripts.script.preset.PresetScripts
import com.github.wrager.sbguserscripts.script.storage.ScriptStorage
import com.github.wrager.sbguserscripts.script.updater.GithubReleaseProvider
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloadResult
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloader
import com.github.wrager.sbguserscripts.script.updater.ScriptUpdateChecker
import com.github.wrager.sbguserscripts.script.updater.ScriptUpdateResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class LauncherViewModel(
    private val scriptStorage: ScriptStorage,
    private val conflictDetector: ConflictDetector,
    private val downloader: ScriptDownloader,
    private val updateChecker: ScriptUpdateChecker,
    private val githubReleaseProvider: GithubReleaseProvider,
    private val appPreferences: SharedPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _events = Channel<LauncherEvent>(Channel.BUFFERED)
    val events: Flow<LauncherEvent> = _events.receiveAsFlow()

    init {
        loadScripts()
    }

    private fun loadScripts() {
        viewModelScope.launch {
            if (!appPreferences.getBoolean(KEY_PRESETS_DOWNLOADED, false)) {
                downloadPresets()
            }
            refreshScriptList()
        }
    }

    private suspend fun downloadPresets() {
        for (preset in PresetScripts.ALL) {
            val result = downloader.download(preset.downloadUrl, isPreset = true)
            when (result) {
                is ScriptDownloadResult.Success -> {
                    if (preset == PresetScripts.SVP) {
                        scriptStorage.setEnabled(result.script.identifier, true)
                    }
                    Log.i(LOG_TAG, "Загружен ${preset.displayName}: ${result.script.header.version}")
                }
                is ScriptDownloadResult.Failure -> {
                    Log.e(LOG_TAG, "Не удалось загрузить ${preset.displayName}: ${result.error}")
                }
            }
        }
        appPreferences.edit().putBoolean(KEY_PRESETS_DOWNLOADED, true).apply()
    }

    fun toggleScript(identifier: ScriptIdentifier, enabled: Boolean) {
        scriptStorage.setEnabled(identifier, enabled)
        refreshScriptList()
    }

    fun addScript(url: String) {
        viewModelScope.launch {
            val result = downloader.download(url, isPreset = false)
            when (result) {
                is ScriptDownloadResult.Success -> {
                    refreshScriptList()
                    _events.send(LauncherEvent.ScriptAdded(result.script.header.name))
                }
                is ScriptDownloadResult.Failure -> {
                    _events.send(
                        LauncherEvent.ScriptAddFailed(
                            result.error.message ?: result.error.toString(),
                        ),
                    )
                }
            }
        }
    }

    fun deleteScript(identifier: ScriptIdentifier) {
        val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return
        scriptStorage.delete(identifier)
        refreshScriptList()
        viewModelScope.launch {
            _events.send(LauncherEvent.ScriptDeleted(script.header.name))
        }
    }

    fun updateAllScripts() {
        viewModelScope.launch {
            val results = updateChecker.checkAllForUpdates()
            val updatedCount = results
                .filterIsInstance<ScriptUpdateResult.UpdateAvailable>()
                .count { updateResult -> applyUpdate(updateResult.identifier) }
            refreshScriptList()
            _events.send(LauncherEvent.UpdatesCompleted(updatedCount))
        }
    }

    fun loadVersions(identifier: ScriptIdentifier) {
        viewModelScope.launch {
            try {
                val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return@launch
                val sourceUrl = script.sourceUrl ?: return@launch
                val (owner, repository) = GithubReleaseProvider.extractOwnerAndRepository(sourceUrl)
                    ?: return@launch
                val releases = githubReleaseProvider.fetchReleases(owner, repository)
                val filename = sourceUrl.substringAfterLast("/")
                val versions = releases.mapNotNull { release ->
                    val asset = release.assets.find { it.name == filename } ?: return@mapNotNull null
                    val versionTag = release.tagName.removePrefix("v")
                    VersionOption(
                        tagName = release.tagName,
                        downloadUrl = asset.downloadUrl,
                        isCurrent = versionTag == script.header.version,
                    )
                }
                _events.send(LauncherEvent.VersionsLoaded(identifier, versions))
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                _events.send(
                    LauncherEvent.VersionInstallFailed(
                        exception.message ?: exception.toString(),
                    ),
                )
            }
        }
    }

    fun installVersion(identifier: ScriptIdentifier, downloadUrl: String) {
        viewModelScope.launch {
            val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return@launch
            val result = downloader.download(downloadUrl, isPreset = script.isPreset)
            when (result) {
                is ScriptDownloadResult.Success -> {
                    scriptStorage.setEnabled(result.script.identifier, script.enabled)
                    refreshScriptList()
                    _events.send(LauncherEvent.VersionInstallCompleted(result.script.header.name))
                }
                is ScriptDownloadResult.Failure -> {
                    _events.send(
                        LauncherEvent.VersionInstallFailed(
                            result.error.message ?: result.error.toString(),
                        ),
                    )
                }
            }
        }
    }

    fun reinstallScript(identifier: ScriptIdentifier) {
        viewModelScope.launch {
            val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return@launch
            val sourceUrl = script.sourceUrl ?: return@launch
            val result = downloader.download(sourceUrl, isPreset = script.isPreset)
            when (result) {
                is ScriptDownloadResult.Success -> {
                    scriptStorage.setEnabled(result.script.identifier, script.enabled)
                    refreshScriptList()
                    _events.send(LauncherEvent.ReinstallCompleted(result.script.header.name))
                }
                is ScriptDownloadResult.Failure -> {
                    _events.send(
                        LauncherEvent.ReinstallFailed(
                            result.error.message ?: result.error.toString(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun applyUpdate(identifier: ScriptIdentifier): Boolean {
        val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return false
        val downloadUrl = script.sourceUrl ?: return false
        val downloadResult = downloader.download(downloadUrl, isPreset = script.isPreset)
        if (downloadResult is ScriptDownloadResult.Success) {
            scriptStorage.setEnabled(downloadResult.script.identifier, script.enabled)
            return true
        }
        return false
    }

    private fun refreshScriptList() {
        val scripts = scriptStorage.getAll()
        val enabledIdentifiers = scripts.filter { it.enabled }.map { it.identifier }.toSet()
        val nameByIdentifier = scripts.associate { it.identifier to it.header.name }

        val items = scripts.map { script ->
            buildScriptUiItem(script, enabledIdentifiers, nameByIdentifier)
        }
        _uiState.value = LauncherUiState(isLoading = false, scripts = items)
    }

    private fun buildScriptUiItem(
        script: UserScript,
        enabledIdentifiers: Set<ScriptIdentifier>,
        nameByIdentifier: Map<ScriptIdentifier, String>,
    ): ScriptUiItem {
        val conflicts = if (script.enabled) {
            conflictDetector.detectConflicts(
                script.identifier,
                enabledIdentifiers - script.identifier,
            )
        } else {
            emptyList()
        }

        val conflictNames = conflicts.map { conflict ->
            nameByIdentifier[conflict.conflictsWith] ?: conflict.conflictsWith.value
        }

        return ScriptUiItem(
            identifier = script.identifier,
            name = script.header.name,
            version = script.header.version,
            author = script.header.author,
            enabled = script.enabled,
            isPreset = script.isPreset,
            conflictNames = conflictNames,
            sourceUrl = script.sourceUrl,
        )
    }

    class Factory(
        private val scriptStorage: ScriptStorage,
        private val conflictDetector: ConflictDetector,
        private val downloader: ScriptDownloader,
        private val updateChecker: ScriptUpdateChecker,
        private val githubReleaseProvider: GithubReleaseProvider,
        private val appPreferences: SharedPreferences,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(
                scriptStorage,
                conflictDetector,
                downloader,
                updateChecker,
                githubReleaseProvider,
                appPreferences,
            ) as T
        }
    }

    companion object {
        private const val KEY_PRESETS_DOWNLOADED = "presetsDownloaded"
        private const val LOG_TAG = "Launcher"
    }
}
