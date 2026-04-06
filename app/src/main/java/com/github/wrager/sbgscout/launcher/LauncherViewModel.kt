package com.github.wrager.sbgscout.launcher

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.wrager.sbgscout.script.installer.ScriptInstallResult
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.ConflictDetector
import com.github.wrager.sbgscout.script.preset.PresetScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.injector.InjectionStateStorage
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import com.github.wrager.sbgscout.script.updater.ScriptDownloadResult
import com.github.wrager.sbgscout.script.updater.ScriptDownloader
import com.github.wrager.sbgscout.script.updater.ScriptReleaseNotesProvider
import com.github.wrager.sbgscout.script.updater.ScriptUpdateChecker
import com.github.wrager.sbgscout.script.updater.ScriptUpdateResult
import kotlinx.coroutines.Job
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
    private val scriptInstaller: ScriptInstaller,
    private val updateChecker: ScriptUpdateChecker,
    private val githubReleaseProvider: GithubReleaseProvider,
    private val injectionStateStorage: InjectionStateStorage,
    private val scriptProvisioner: DefaultScriptProvisioner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _events = Channel<LauncherEvent>(Channel.BUFFERED)
    val events: Flow<LauncherEvent> = _events.receiveAsFlow()

    private val operationStateMap = mutableMapOf<ScriptIdentifier, ScriptOperationState>()
    private val activeDownloadJobs = mutableMapOf<ScriptIdentifier, Job>()

    init {
        loadScripts()
    }

    private fun loadScripts() {
        viewModelScope.launch {
            refreshScriptList()
        }
    }

    private fun setOperationState(identifier: ScriptIdentifier, state: ScriptOperationState?) {
        if (state != null) {
            operationStateMap[identifier] = state
        } else {
            operationStateMap.remove(identifier)
        }
        refreshScriptList()
    }

    fun downloadScript(identifier: ScriptIdentifier) {
        if (operationStateMap[identifier] is ScriptOperationState.Downloading) return
        val job = viewModelScope.launch {
            val preset = PresetScripts.ALL.find { it.identifier == identifier } ?: return@launch
            setOperationState(identifier, ScriptOperationState.Downloading(0))
            try {
                val result = downloader.download(preset.downloadUrl, isPreset = true) { progress ->
                    setOperationState(identifier, ScriptOperationState.Downloading(progress))
                }
                when (result) {
                    is ScriptDownloadResult.Success -> {
                        if (preset.enabledByDefault) {
                            scriptStorage.setEnabled(result.script.identifier, true)
                        }
                        scriptProvisioner.markProvisioned(preset.identifier)
                        setOperationState(result.script.identifier, ScriptOperationState.UpToDate)
                        Log.i(LOG_TAG, "Загружен ${preset.displayName}: ${result.script.header.version}")
                        _events.send(
                            LauncherEvent.ScriptAdded(
                                result.script.header.name,
                                result.script.header.version,
                            ),
                        )
                    }
                    is ScriptDownloadResult.Failure -> {
                        Log.e(LOG_TAG, "Не удалось загрузить ${preset.displayName}: ${result.error}")
                        _events.send(
                            LauncherEvent.ScriptAddFailed(
                                result.error.message ?: result.error.toString(),
                            ),
                        )
                    }
                }
            } finally {
                // Очищаем состояние загрузки, если оно не было заменено на результат
                if (operationStateMap[identifier] is ScriptOperationState.Downloading) {
                    setOperationState(identifier, null)
                }
                activeDownloadJobs.remove(identifier)
            }
        }
        activeDownloadJobs[identifier] = job
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
                    _events.send(
                        LauncherEvent.ScriptAdded(
                            result.script.header.name,
                            result.script.header.version,
                        ),
                    )
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

    fun addScriptFromContent(content: String) {
        viewModelScope.launch {
            val parseResult = scriptInstaller.parse(content)
            when (parseResult) {
                is ScriptInstallResult.InvalidHeader -> {
                    _events.send(LauncherEvent.ScriptAddFailed("No UserScript header found"))
                }
                is ScriptInstallResult.Parsed -> {
                    val matchingPreset = findMatchingPreset(parseResult.script)
                    val script = if (matchingPreset != null) {
                        parseResult.script.copy(
                            sourceUrl = matchingPreset.downloadUrl,
                            updateUrl = matchingPreset.updateUrl,
                            isPreset = true,
                        )
                    } else {
                        parseResult.script
                    }
                    if (matchingPreset != null) {
                        cleanupExistingPresetScript(matchingPreset, script)
                    }
                    scriptInstaller.save(script)
                    if (matchingPreset != null) {
                        if (matchingPreset.enabledByDefault) {
                            scriptStorage.setEnabled(script.identifier, true)
                        }
                        scriptProvisioner.markProvisioned(matchingPreset.identifier)
                    }
                    refreshScriptList()
                    _events.send(
                        LauncherEvent.ScriptAdded(script.header.name, script.header.version),
                    )
                }
            }
        }
    }

    /**
     * Определяет, соответствует ли скрипт одному из пресетов.
     *
     * Матчит по @downloadURL в заголовке (приоритет) или по совпадению
     * namespace-префикса И @name. Одного namespace недостаточно: в том же
     * namespace могут быть другие скрипты (диагностические, форки и т.д.).
     */
    private fun findMatchingPreset(script: UserScript): PresetScript? {
        return PresetScripts.ALL.find { preset ->
            script.header.downloadUrl == preset.downloadUrl ||
                (script.identifier.value.startsWith(preset.identifier.value + "/") &&
                    script.header.name == preset.displayName)
        }
    }

    fun deleteScript(identifier: ScriptIdentifier) {
        activeDownloadJobs[identifier]?.cancel()
        activeDownloadJobs.remove(identifier)
        val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return
        scriptStorage.delete(identifier)
        setOperationState(identifier, null)
        viewModelScope.launch {
            _events.send(LauncherEvent.ScriptDeleted(script.header.name))
        }
    }

    fun checkUpdates() {
        val isAlreadyChecking = operationStateMap.values.any { it is ScriptOperationState.CheckingUpdate }
        if (isAlreadyChecking) return
        viewModelScope.launch {
            try {
                val updatableIdentifiers = scriptStorage.getAll()
                    .filter { it.updateUrl != null }
                    .map { it.identifier }
                    .toSet()
                // Очищаем старые результаты проверки, сохраняя активные загрузки
                operationStateMap.entries.removeAll { (_, state) ->
                    state is ScriptOperationState.UpToDate || state is ScriptOperationState.UpdateAvailable
                }
                for (identifier in updatableIdentifiers) {
                    if (operationStateMap[identifier] !is ScriptOperationState.Downloading) {
                        operationStateMap[identifier] = ScriptOperationState.CheckingUpdate
                    }
                }
                refreshScriptList()
                val results = updateChecker.checkAllForUpdates()
                for (result in results) {
                    val (identifier, state) = when (result) {
                        is ScriptUpdateResult.UpToDate ->
                            result.identifier to ScriptOperationState.UpToDate
                        is ScriptUpdateResult.UpdateAvailable ->
                            result.identifier to ScriptOperationState.UpdateAvailable
                        is ScriptUpdateResult.CheckFailed ->
                            result.identifier to null
                    }
                    if (state != null) {
                        operationStateMap[identifier] = state
                    } else {
                        operationStateMap.remove(identifier)
                    }
                }
                refreshScriptList()
                val available = results.filterIsInstance<ScriptUpdateResult.UpdateAvailable>()
                val releaseNotesSummary = fetchReleaseNotesSummary(available)
                _events.send(LauncherEvent.CheckCompleted(available.size, releaseNotesSummary))
            } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                operationStateMap.entries.removeAll { (_, state) ->
                    state is ScriptOperationState.CheckingUpdate
                }
                Log.w(LOG_TAG, "Проверка обновлений завершилась с ошибкой", exception)
                refreshScriptList()
            }
        }
    }

    fun updateScript(identifier: ScriptIdentifier) {
        viewModelScope.launch {
            setOperationState(identifier, ScriptOperationState.Downloading(0))
            val newIdentifier = applyUpdate(identifier) { progress ->
                setOperationState(identifier, ScriptOperationState.Downloading(progress))
            }
            val updatedCount = if (newIdentifier != null) {
                setOperationState(newIdentifier, ScriptOperationState.UpToDate)
                1
            } else {
                setOperationState(identifier, null)
                0
            }
            _events.send(LauncherEvent.UpdatesCompleted(updatedCount))
        }
    }

    /** Скачивает все обновления, уже помеченные как [ScriptOperationState.UpdateAvailable]. */
    fun updateAll() {
        viewModelScope.launch {
            val toUpdate = operationStateMap
                .filter { it.value is ScriptOperationState.UpdateAvailable }
                .keys.toList()
            if (toUpdate.isEmpty()) return@launch
            var updatedCount = 0
            for (identifier in toUpdate) {
                setOperationState(identifier, ScriptOperationState.Downloading(0))
                val newIdentifier = applyUpdate(identifier) { progress ->
                    setOperationState(identifier, ScriptOperationState.Downloading(progress))
                }
                if (newIdentifier != null) {
                    setOperationState(newIdentifier, ScriptOperationState.UpToDate)
                    updatedCount++
                } else {
                    setOperationState(identifier, null)
                }
            }
            _events.send(LauncherEvent.UpdatesCompleted(updatedCount))
        }
    }

    /** Проверяет обновления и скачивает все доступные (одна операция). */
    fun checkAndUpdateAll() {
        val isAlreadyChecking = operationStateMap.values.any { it is ScriptOperationState.CheckingUpdate }
        if (isAlreadyChecking) return
        viewModelScope.launch {
            // Фаза 1: проверка (аналог checkUpdates, но без отправки CheckCompleted)
            val updatableIdentifiers = scriptStorage.getAll()
                .filter { it.updateUrl != null }
                .map { it.identifier }
                .toSet()
            operationStateMap.entries.removeAll { (_, state) ->
                state is ScriptOperationState.UpToDate || state is ScriptOperationState.UpdateAvailable
            }
            for (identifier in updatableIdentifiers) {
                if (operationStateMap[identifier] !is ScriptOperationState.Downloading) {
                    operationStateMap[identifier] = ScriptOperationState.CheckingUpdate
                }
            }
            refreshScriptList()
            val results = updateChecker.checkAllForUpdates()
            for (result in results) {
                val (identifier, state) = when (result) {
                    is ScriptUpdateResult.UpToDate ->
                        result.identifier to ScriptOperationState.UpToDate
                    is ScriptUpdateResult.UpdateAvailable ->
                        result.identifier to ScriptOperationState.UpdateAvailable
                    is ScriptUpdateResult.CheckFailed ->
                        result.identifier to null
                }
                if (state != null) operationStateMap[identifier] = state
                else operationStateMap.remove(identifier)
            }
            refreshScriptList()

            // Фаза 2: обновление всех доступных
            val toUpdate = operationStateMap
                .filter { it.value is ScriptOperationState.UpdateAvailable }
                .keys.toList()
            var updatedCount = 0
            for (identifier in toUpdate) {
                setOperationState(identifier, ScriptOperationState.Downloading(0))
                val newIdentifier = applyUpdate(identifier) { progress ->
                    setOperationState(identifier, ScriptOperationState.Downloading(progress))
                }
                if (newIdentifier != null) {
                    setOperationState(newIdentifier, ScriptOperationState.UpToDate)
                    updatedCount++
                } else {
                    setOperationState(identifier, null)
                }
            }
            _events.send(LauncherEvent.UpdatesCompleted(updatedCount))
        }
    }

    /**
     * Загружает и агрегирует release notes для списка доступных обновлений.
     *
     * Формат: "ScriptName 1.0.0 → 2.0.0\n<release notes>\n\nOtherScript ..."
     * Возвращает null, если обновлений нет или ни у одного нет release notes.
     */
    private suspend fun fetchReleaseNotesSummary(
        updates: List<ScriptUpdateResult.UpdateAvailable>,
    ): String? {
        if (updates.isEmpty()) return null
        val notesProvider = ScriptReleaseNotesProvider(githubReleaseProvider)
        val scripts = scriptStorage.getAll()
        val sections = updates.mapNotNull { update ->
            val script = scripts.find { it.identifier == update.identifier }
            val name = script?.header?.name ?: update.identifier.value
            val header = "$name ${update.currentVersion.value} \u2192 ${update.latestVersion.value}"
            val notes = script?.sourceUrl?.let { sourceUrl ->
                try {
                    notesProvider.fetchReleaseNotes(sourceUrl, update.currentVersion)
                } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
                    Log.w(LOG_TAG, "Не удалось загрузить release notes для ${update.identifier}", exception)
                    null
                }
            }
            if (notes != null) "$header\n$notes" else header
        }
        return sections.joinToString("\n\n").ifEmpty { null }
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
                    // releaseTag заполняется при установке через диалог версий
                    // и позволяет определить текущую версию, даже если @version
                    // в заголовке скрипта не совпадает с тегом релиза
                    val isCurrent = if (script.releaseTag != null) {
                        release.tagName == script.releaseTag
                    } else {
                        versionTag == script.header.version
                    }
                    VersionOption(
                        tagName = release.tagName,
                        downloadUrl = asset.downloadUrl,
                        isCurrent = isCurrent,
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

    fun installVersion(
        identifier: ScriptIdentifier,
        downloadUrl: String,
        isLatest: Boolean,
        tagName: String,
    ) {
        viewModelScope.launch {
            val script = scriptStorage.getAll().find { it.identifier == identifier } ?: return@launch
            setOperationState(identifier, ScriptOperationState.Downloading(0))
            val result = downloader.download(downloadUrl, isPreset = script.isPreset) { progress ->
                setOperationState(identifier, ScriptOperationState.Downloading(progress))
            }
            when (result) {
                is ScriptDownloadResult.Success -> {
                    cleanupOldIdentifier(identifier, result.script.identifier)
                    // Сохраняем тег релиза, чтобы в списке версий можно было
                    // определить текущую, даже если @version в заголовке скрипта
                    // не совпадает с тегом (например, CUI в репо EUI)
                    val scriptWithTag = result.script.copy(
                        releaseTag = tagName,
                        enabled = script.enabled,
                    )
                    scriptStorage.save(scriptWithTag)
                    val resultState = if (isLatest) {
                        ScriptOperationState.UpToDate
                    } else {
                        ScriptOperationState.UpdateAvailable
                    }
                    setOperationState(scriptWithTag.identifier, resultState)
                    _events.send(
                        LauncherEvent.VersionInstallCompleted(
                            scriptWithTag.header.name,
                            scriptWithTag.header.version,
                        ),
                    )
                }
                is ScriptDownloadResult.Failure -> {
                    setOperationState(identifier, null)
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
            setOperationState(identifier, ScriptOperationState.Downloading(0))
            val result = downloader.download(sourceUrl, isPreset = script.isPreset) { progress ->
                setOperationState(identifier, ScriptOperationState.Downloading(progress))
            }
            when (result) {
                is ScriptDownloadResult.Success -> {
                    cleanupOldIdentifier(identifier, result.script.identifier)
                    scriptStorage.setEnabled(result.script.identifier, script.enabled)
                    setOperationState(result.script.identifier, ScriptOperationState.UpToDate)
                    _events.send(
                        LauncherEvent.ReinstallCompleted(
                            result.script.header.name,
                            result.script.header.version,
                        ),
                    )
                }
                is ScriptDownloadResult.Failure -> {
                    setOperationState(identifier, null)
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
     * Удаляет существующий скрипт того же пресета, если его идентификатор отличается.
     *
     * При импорте из файла @name/@namespace могут отличаться от ранее загруженного
     * скрипта, из-за чего идентификатор меняется. Без очистки старая запись остаётся
     * в хранилище: preset loop находит только одну из двух, а вторая выпадает из
     * обоих списков (отфильтрована по isPreset из customItems, не подхвачена в presetItems).
     */
    private fun cleanupExistingPresetScript(preset: PresetScript, newScript: UserScript) {
        val existing = scriptStorage.getAll().find { stored ->
            stored.identifier != newScript.identifier &&
                (stored.identifier == preset.identifier ||
                    (stored.isPreset && stored.sourceUrl == preset.downloadUrl))
        } ?: return
        scriptStorage.delete(existing.identifier)
        operationStateMap.remove(existing.identifier)
    }

    /**
     * Если идентификатор скрипта изменился после загрузки новой версии
     * (например, изменился @name или @namespace в заголовке),
     * удаляет старую запись из хранилища и состояния операции.
     */
    private fun cleanupOldIdentifier(
        oldIdentifier: ScriptIdentifier,
        newIdentifier: ScriptIdentifier,
    ) {
        if (newIdentifier != oldIdentifier) {
            scriptStorage.delete(oldIdentifier)
            operationStateMap.remove(oldIdentifier)
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
        val canonicalEnabledVersions: Map<ScriptIdentifier, ScriptVersion?> = storedScripts
            .filter { it.enabled }
            .associate { resolvePresetIdentifier(it) to it.header.version?.let(::ScriptVersion) }
        val nameByIdentifier = storedScripts.associate { resolvePresetIdentifier(it) to it.header.name }

        val presetClaimedIdentifiers = mutableSetOf<ScriptIdentifier>()
        val presetItems = PresetScripts.ALL.map { preset ->
            val script = storedScripts.find { it.identifier == preset.identifier }
                ?: storedScripts.find { it.isPreset && it.sourceUrl == preset.downloadUrl }
            if (script != null) {
                presetClaimedIdentifiers.add(script.identifier)
                buildScriptUiItem(script, canonicalEnabledVersions, nameByIdentifier)
            } else {
                ScriptUiItem(
                    identifier = preset.identifier,
                    name = preset.displayName,
                    version = null,
                    releaseTag = null,
                    author = null,
                    enabled = false,
                    isPreset = true,
                    conflictNames = emptyList(),
                    sourceUrl = preset.downloadUrl,
                    isDownloaded = false,
                    operationState = operationStateMap[preset.identifier],
                )
            }
        }

        val customItems = storedScripts
            .filter { it.identifier !in presetClaimedIdentifiers }
            .map { buildScriptUiItem(it, canonicalEnabledVersions, nameByIdentifier) }

        val currentEnabledSnapshot = storedScripts
            .filter { it.enabled }
            .map { InjectionStateStorage.buildSnapshotEntry(it) }
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
        canonicalEnabledVersions: Map<ScriptIdentifier, ScriptVersion?>,
        nameByIdentifier: Map<ScriptIdentifier, String>,
    ): ScriptUiItem {
        val canonicalIdentifier = resolvePresetIdentifier(script)
        val conflicts = if (script.enabled) {
            conflictDetector.detectConflicts(
                candidateIdentifier = canonicalIdentifier,
                candidateVersion = script.header.version?.let(::ScriptVersion),
                enabledVersions = canonicalEnabledVersions - canonicalIdentifier,
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
            releaseTag = script.releaseTag,
            author = script.header.author,
            enabled = script.enabled,
            isPreset = script.isPreset,
            conflictNames = conflictNames,
            sourceUrl = script.sourceUrl,
            isDownloaded = true,
            isUpdatable = script.updateUrl != null,
            operationState = operationStateMap[script.identifier],
        )
    }

    class Factory(
        private val scriptStorage: ScriptStorage,
        private val conflictDetector: ConflictDetector,
        private val downloader: ScriptDownloader,
        private val scriptInstaller: ScriptInstaller,
        private val updateChecker: ScriptUpdateChecker,
        private val githubReleaseProvider: GithubReleaseProvider,
        private val injectionStateStorage: InjectionStateStorage,
        private val scriptProvisioner: DefaultScriptProvisioner,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(
                scriptStorage,
                conflictDetector,
                downloader,
                scriptInstaller,
                updateChecker,
                githubReleaseProvider,
                injectionStateStorage,
                scriptProvisioner,
            ) as T
        }
    }

    companion object {
        private const val LOG_TAG = "Launcher"
    }
}
