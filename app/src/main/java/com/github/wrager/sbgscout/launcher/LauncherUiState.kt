package com.github.wrager.sbgscout.launcher

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider

data class LauncherUiState(
    val isLoading: Boolean = true,
    val scripts: List<ScriptUiItem> = emptyList(),
    val reloadNeeded: Boolean = false,
)

sealed class ScriptOperationState {
    data class Downloading(val progress: Int) : ScriptOperationState()
    data object CheckingUpdate : ScriptOperationState()
    data object UpToDate : ScriptOperationState()
    data object UpdateAvailable : ScriptOperationState()
}

data class ScriptUiItem(
    val identifier: ScriptIdentifier,
    val name: String,
    val version: String?,
    val releaseTag: String?,
    val author: String?,
    val enabled: Boolean,
    val isPreset: Boolean,
    val conflictNames: List<String>,
    val sourceUrl: String?,
    val isDownloaded: Boolean = true,
    val operationState: ScriptOperationState? = null,
    val isUpdatable: Boolean = false,
) {
    val isGithubHosted: Boolean
        get() = sourceUrl != null &&
            GithubReleaseProvider.extractOwnerAndRepository(sourceUrl) != null
}

data class VersionOption(
    val tagName: String,
    val downloadUrl: String,
    val isCurrent: Boolean,
)

sealed class LauncherEvent {
    data class ScriptAdded(val scriptName: String, val scriptVersion: String?) : LauncherEvent()
    data class ScriptAddFailed(val errorMessage: String) : LauncherEvent()
    data class ScriptDeleted(val scriptName: String) : LauncherEvent()
    data class UpdatesCompleted(val updatedCount: Int) : LauncherEvent()
    data class VersionsLoaded(
        val identifier: ScriptIdentifier,
        val versions: List<VersionOption>,
    ) : LauncherEvent()
    data class VersionInstallCompleted(val scriptName: String, val scriptVersion: String?) : LauncherEvent()
    data class VersionInstallFailed(val errorMessage: String) : LauncherEvent()
    data class ReinstallCompleted(val scriptName: String, val scriptVersion: String?) : LauncherEvent()
    data class ReinstallFailed(val errorMessage: String) : LauncherEvent()
    data class CheckCompleted(
        val availableCount: Int,
        /** Агрегированные release notes обновлений (null, если нет заметок или обновлений). */
        val releaseNotesSummary: String? = null,
    ) : LauncherEvent()
}
