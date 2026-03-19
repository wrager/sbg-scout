package com.github.wrager.sbguserscripts.launcher

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import com.github.wrager.sbguserscripts.script.preset.ConflictDetector
import com.github.wrager.sbguserscripts.script.preset.PresetScripts
import com.github.wrager.sbguserscripts.script.storage.ScriptStorage
import com.github.wrager.sbguserscripts.script.injector.InjectionStateStorage
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
    private val injectionStateStorage: InjectionStateStorage,
    private val autoUpdateEnabled: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _events = Channel<LauncherEvent>(Channel.BUFFERED)
    val events: Flow<LauncherEvent> = _events.receiveAsFlow()

    private val downloadProgressMap = mutableMapOf<ScriptIdentifier, Int>()
    private val checkingUpdateIdentifiers = mutableSetOf<ScriptIdentifier>()
    private val upToDateIdentifiers = mutableSetOf<ScriptIdentifier>()
    private val updateAvailableIdentifiers = mutableSetOf<ScriptIdentifier>()

    init {
        loadScripts()
    }

    private fun loadScripts() {
        viewModelScope.launch {
            refreshScriptList()
            if (autoUpdateEnabled) {
                checkUpdatesInBackground()
            }
        }
    }

    private fun checkUpdatesInBackground() {
        viewModelScope.launch {
            try {
                val results = updateChecker.checkAllForUpdates()
                results.filterIsInstance<ScriptUpdateResult.UpToDate>().forEach { result ->
                    updateAvailableIdentifiers.remove(result.identifier)
                    upToDateIdentifiers.add(result.identifier)
                }
                results.filterIsInstance<ScriptUpdateResult.UpdateAvailable>().forEach { result ->
                    upToDateIdentifiers.remove(result.identifier)
                    updateAvailableIdentifiers.add(result.identifier)
                }
                refreshScriptList()
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                Log.w(LOG_TAG, "Фоновая проверка обновлений завершилась с ошибкой", exception)
            }
        }
    }

    fun downloadScript(identifier: ScriptIdentifier) {
        viewModelScope.launch {
            val preset = PresetScripts.ALL.find { it.identifier == identifier } ?: return@launch
            downloadProgressMap[identifier] = 0
            refreshScriptList()
            val result = downloader.download(preset.downloadUrl, isPreset = true) { progress ->
                downloadProgressMap[identifier] = progress
                refreshScriptList()
            }
            downloadProgressMap.remove(identifier)
            when (result) {
                is ScriptDownloadResult.Success -> {
                    updateAvailableIdentifiers.remove(result.script.identifier)
                    upToDateIdentifiers.add(result.script.identifier)
                    refreshScriptList()
                    Log.i(LOG_TAG, "Загружен ${preset.displayName}: ${result.script.header.version}")
                }
                is ScriptDownloadResult.Failure -> {
                    refreshScriptList()
                    Log.e(LOG_TAG, "Не удалось загрузить ${preset.displayName}: ${result.error}")
                    _events.send(
                        LauncherEvent.ScriptAddFailed(
                            result.error.message ?: result.error.toString(),
                        ),
                    )
                }
            }
        }
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

    fun checkUpdates() {
        viewModelScope.launch {
            try {
                upToDateIdentifiers.clear()
                updateAvailableIdentifiers.clear()
                val downloadedIdentifiers = scriptStorage.getAll().map { it.identifier }.toSet()
                checkingUpdateIdentifiers.addAll(downloadedIdentifiers)
                refreshScriptList()
                val results = updateChecker.checkAllForUpdates()
                checkingUpdateIdentifiers.clear()
                results.filterIsInstance<ScriptUpdateResult.UpToDate>().forEach { result ->
                    updateAvailableIdentifiers.remove(result.identifier)
                    upToDateIdentifiers.add(result.identifier)
                }
                results.filterIsInstance<ScriptUpdateResult.UpdateAvailable>().forEach { result ->
                    upToDateIdentifiers.remove(result.identifier)
                    updateAvailableIdentifiers.add(result.identifier)
                }
                refreshScriptList()
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                checkingUpdateIdentifiers.clear()
                Log.w(LOG_TAG, "Проверка обновлений завершилась с ошибкой", exception)
                refreshScriptList()
            }
        }
    }

    fun updateAllScripts() {
        viewModelScope.launch {
            val results = updateChecker.checkAllForUpdates()
            var updatedCount = 0
            results.filterIsInstance<ScriptUpdateResult.UpdateAvailable>().forEach { updateResult ->
                downloadProgressMap[updateResult.identifier] = 0
                refreshScriptList()
                val newIdentifier = applyUpdate(updateResult.identifier) { progress ->
                    downloadProgressMap[updateResult.identifier] = progress
                    refreshScriptList()
                }
                downloadProgressMap.remove(updateResult.identifier)
                if (newIdentifier != null) {
                    updateAvailableIdentifiers.remove(updateResult.identifier)
                    upToDateIdentifiers.add(newIdentifier)
                    updatedCount++
                }
            }
            refreshScriptList()
            _events.send(LauncherEvent.UpdatesCompleted(updatedCount))
        }
    }

    fun updateScript(identifier: ScriptIdentifier) {
        viewModelScope.launch {
            downloadProgressMap[identifier] = 0
            refreshScriptList()
            val newIdentifier = applyUpdate(identifier) { progress ->
                downloadProgressMap[identifier] = progress
                refreshScriptList()
            }
            downloadProgressMap.remove(identifier)
            if (newIdentifier != null) {
                updateAvailableIdentifiers.remove(identifier)
                upToDateIdentifiers.add(newIdentifier)
            }
            refreshScriptList()
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

    fun installVersion(identifier: ScriptIdentifier, downloadUrl: String, isLatest: Boolean) {
        viewModelScope.launch {
            val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return@launch
            val result = downloader.download(downloadUrl, isPreset = script.isPreset)
            when (result) {
                is ScriptDownloadResult.Success -> {
                    cleanupOldIdentifier(identifier, result.script.identifier)
                    scriptStorage.setEnabled(result.script.identifier, script.enabled)
                    upToDateIdentifiers.remove(result.script.identifier)
                    updateAvailableIdentifiers.remove(result.script.identifier)
                    if (isLatest) {
                        upToDateIdentifiers.add(result.script.identifier)
                    } else {
                        updateAvailableIdentifiers.add(result.script.identifier)
                    }
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
                    cleanupOldIdentifier(identifier, result.script.identifier)
                    scriptStorage.setEnabled(result.script.identifier, script.enabled)
                    updateAvailableIdentifiers.remove(result.script.identifier)
                    upToDateIdentifiers.add(result.script.identifier)
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

    private suspend fun applyUpdate(
        identifier: ScriptIdentifier,
        onProgress: ((Int) -> Unit)? = null,
    ): ScriptIdentifier? {
        val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return null
        val downloadUrl = script.sourceUrl ?: return null
        val downloadResult = downloader.download(downloadUrl, isPreset = script.isPreset, onProgress)
        if (downloadResult is ScriptDownloadResult.Success) {
            cleanupOldIdentifier(identifier, downloadResult.script.identifier)
            scriptStorage.setEnabled(downloadResult.script.identifier, script.enabled)
            return downloadResult.script.identifier
        }
        return null
    }

    /**
     * Если идентификатор скрипта изменился после загрузки новой версии
     * (например, изменился @name или @namespace в заголовке),
     * удаляет старую запись из хранилища и множеств статусов.
     */
    private fun cleanupOldIdentifier(
        oldIdentifier: ScriptIdentifier,
        newIdentifier: ScriptIdentifier,
    ) {
        if (newIdentifier != oldIdentifier) {
            scriptStorage.delete(oldIdentifier)
            upToDateIdentifiers.remove(oldIdentifier)
            updateAvailableIdentifiers.remove(oldIdentifier)
            checkingUpdateIdentifiers.remove(oldIdentifier)
        }
    }

    private fun resolvePresetIdentifier(script: UserScript): ScriptIdentifier {
        return PresetScripts.ALL.find { preset ->
            preset.identifier == script.identifier ||
                (script.isPreset && script.sourceUrl == preset.downloadUrl)
        }?.identifier ?: script.identifier
    }

    private fun refreshScriptList() {
        val storedScripts = scriptStorage.getAll()
        val canonicalEnabledIdentifiers = storedScripts
            .filter { it.enabled }
            .map { resolvePresetIdentifier(it) }
            .toSet()
        val nameByIdentifier = storedScripts.associate { resolvePresetIdentifier(it) to it.header.name }

        val presetItems = PresetScripts.ALL.map { preset ->
            val script = storedScripts.find { it.identifier == preset.identifier }
                ?: storedScripts.find { it.isPreset && it.sourceUrl == preset.downloadUrl }
            if (script != null) {
                buildScriptUiItem(script, canonicalEnabledIdentifiers, nameByIdentifier)
            } else {
                ScriptUiItem(
                    identifier = preset.identifier,
                    name = preset.displayName,
                    version = null,
                    author = null,
                    enabled = false,
                    isPreset = true,
                    conflictNames = emptyList(),
                    sourceUrl = preset.downloadUrl,
                    isDownloaded = false,
                    downloadProgress = downloadProgressMap[preset.identifier],
                    isUpToDate = false,
                )
            }
        }

        val customItems = storedScripts
            .filter { !it.isPreset }
            .map { buildScriptUiItem(it, canonicalEnabledIdentifiers, nameByIdentifier) }

        val currentEnabledSnapshot = storedScripts
            .filter { it.enabled }
            .map { "${it.identifier.value}::${it.header.version ?: ""}" }
            .toSet()
        // null — игра не загружалась, ничего перезагружать незачем
        val lastInjectedSnapshot = injectionStateStorage.getSnapshot()
        val reloadNeeded = lastInjectedSnapshot != null &&
            currentEnabledSnapshot != lastInjectedSnapshot

        _uiState.value = LauncherUiState(
            isLoading = false,
            scripts = presetItems + customItems,
            reloadNeeded = reloadNeeded,
        )
    }

    private fun buildScriptUiItem(
        script: UserScript,
        canonicalEnabledIdentifiers: Set<ScriptIdentifier>,
        nameByIdentifier: Map<ScriptIdentifier, String>,
    ): ScriptUiItem {
        val canonicalIdentifier = resolvePresetIdentifier(script)
        val conflicts = if (script.enabled) {
            conflictDetector.detectConflicts(
                canonicalIdentifier,
                canonicalEnabledIdentifiers - canonicalIdentifier,
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
            isDownloaded = true,
            downloadProgress = downloadProgressMap[script.identifier],
            isCheckingUpdate = script.identifier in checkingUpdateIdentifiers,
            isUpToDate = script.identifier in upToDateIdentifiers,
            hasUpdateAvailable = script.identifier in updateAvailableIdentifiers,
        )
    }

    class Factory(
        private val scriptStorage: ScriptStorage,
        private val conflictDetector: ConflictDetector,
        private val downloader: ScriptDownloader,
        private val updateChecker: ScriptUpdateChecker,
        private val githubReleaseProvider: GithubReleaseProvider,
        private val injectionStateStorage: InjectionStateStorage,
        private val autoUpdateEnabled: Boolean,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(
                scriptStorage,
                conflictDetector,
                downloader,
                updateChecker,
                githubReleaseProvider,
                injectionStateStorage,
                autoUpdateEnabled,
            ) as T
        }
    }

    companion object {
        private const val LOG_TAG = "Launcher"
    }
}
