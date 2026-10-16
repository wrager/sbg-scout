package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion

sealed class ScriptUpdateResult {
    data class UpdateAvailable(
        val identifier: ScriptIdentifier,
        val currentVersion: ScriptVersion,
        val latestVersion: ScriptVersion,
    ) : ScriptUpdateResult()

    data class UpToDate(
        val identifier: ScriptIdentifier,
    ) : ScriptUpdateResult()

    data class CheckFailed(
        val identifier: ScriptIdentifier,
        val error: Throwable,
    ) : ScriptUpdateResult()
}
