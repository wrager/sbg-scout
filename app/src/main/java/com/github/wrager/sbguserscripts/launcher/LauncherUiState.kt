package com.github.wrager.sbguserscripts.launcher

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.updater.GithubReleaseProvider

data class LauncherUiState(
    val isLoading: Boolean = true,
    val scripts: List<ScriptUiItem> = emptyList(),
)

data class ScriptUiItem(
    val identifier: ScriptIdentifier,
    val name: String,
    val version: String?,
    val author: String?,
    val enabled: Boolean,
    val isPreset: Boolean,
    val conflictNames: List<String>,
    val sourceUrl: String?,
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
    data class ScriptAdded(val scriptName: String) : LauncherEvent()
    data class ScriptAddFailed(val errorMessage: String) : LauncherEvent()
    data class ScriptDeleted(val scriptName: String) : LauncherEvent()
    data class UpdatesCompleted(val updatedCount: Int) : LauncherEvent()
    data class VersionsLoaded(
        val identifier: ScriptIdentifier,
        val versions: List<VersionOption>,
    ) : LauncherEvent()
    data class VersionInstallCompleted(val scriptName: String) : LauncherEvent()
    data class VersionInstallFailed(val errorMessage: String) : LauncherEvent()
    data class ReinstallCompleted(val scriptName: String) : LauncherEvent()
    data class ReinstallFailed(val errorMessage: String) : LauncherEvent()
}
