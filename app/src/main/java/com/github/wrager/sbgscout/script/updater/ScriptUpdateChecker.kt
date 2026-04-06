package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.parser.HeaderParser
import com.github.wrager.sbgscout.script.storage.ScriptStorage

class ScriptUpdateChecker(
    private val httpFetcher: HttpFetcher,
    private val scriptStorage: ScriptStorage,
) {
    suspend fun checkForUpdate(script: UserScript): ScriptUpdateResult {
        val updateUrl = script.updateUrl
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("No update URL configured"),
            )

        return try {
            compareVersions(script, updateUrl)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            ScriptUpdateResult.CheckFailed(script.identifier, exception)
        }
    }

    private suspend fun compareVersions(
        script: UserScript,
        updateUrl: String,
    ): ScriptUpdateResult {
        val metaContent = httpFetcher.fetch(updateUrl)
        val remoteHeader = HeaderParser.parse(metaContent)
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("Failed to parse remote header"),
            )

        val currentVersionString = script.header.version
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("Current script has no version"),
            )

        val remoteVersionString = remoteHeader.version
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("Remote script has no version"),
            )

        val current = ScriptVersion(currentVersionString)
        val latest = ScriptVersion(remoteVersionString)

        return if (latest > current) {
            ScriptUpdateResult.UpdateAvailable(
                identifier = script.identifier,
                currentVersion = current,
                latestVersion = latest,
            )
        } else {
            ScriptUpdateResult.UpToDate(script.identifier)
        }
    }

    suspend fun checkAllForUpdates(): List<ScriptUpdateResult> {
        return scriptStorage.getAll()
            .filter { it.updateUrl != null }
            .map { checkForUpdate(it) }
    }
}
