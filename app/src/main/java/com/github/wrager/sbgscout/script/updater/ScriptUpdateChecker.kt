package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.parser.HeaderParser
import com.github.wrager.sbgscout.script.storage.ScriptStorage

class ScriptUpdateChecker(
    private val httpFetcher: HttpFetcher,
    private val scriptStorage: ScriptStorage,
    private val githubReleaseProvider: GithubReleaseProvider,
) {
    suspend fun checkForUpdate(script: UserScript): ScriptUpdateResult {
        val updateUrl = script.updateUrl
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("No update URL configured"),
            )

        return try {
            val githubMatch = GITHUB_RELEASE_ASSET_PATTERN.find(updateUrl)
            if (githubMatch != null) {
                compareVersionsViaGithubApi(
                    script,
                    owner = githubMatch.groupValues[1],
                    repository = githubMatch.groupValues[2],
                )
            } else {
                compareVersionsViaHttpFetcher(script, updateUrl)
            }
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            ScriptUpdateResult.CheckFailed(script.identifier, exception)
        }
    }

    private suspend fun compareVersionsViaGithubApi(
        script: UserScript,
        owner: String,
        repository: String,
    ): ScriptUpdateResult {
        val currentVersionString = script.header.version
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("Current script has no version"),
            )

        val releases = githubReleaseProvider.fetchReleases(owner, repository)
        val latest = releases.firstOrNull()
            ?: return ScriptUpdateResult.CheckFailed(
                script.identifier,
                IllegalStateException("No releases in $owner/$repository"),
            )

        val current = ScriptVersion(currentVersionString)
        val latestVersion = ScriptVersion(latest.tagName.removePrefix("v"))

        if (latestVersion < current) {
            // tag_name меньше установленной версии — значит тег не соответствует
            // этому скрипту (mono-repo, где один тег на несколько asset'ов с
            // разными версиями, например egorantonov/sbg-enhanced: тег = EUI version,
            // но CUI в том же релизе имеет свою @version). Fallback на legacy-путь,
            // который скачает файл и распарсит реальный @version из хедера.
            return compareVersionsViaHttpFetcher(script, script.updateUrl!!)
        }

        return if (latestVersion > current) {
            ScriptUpdateResult.UpdateAvailable(
                identifier = script.identifier,
                currentVersion = current,
                latestVersion = latestVersion,
            )
        } else {
            ScriptUpdateResult.UpToDate(script.identifier)
        }
    }

    private suspend fun compareVersionsViaHttpFetcher(
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

    private companion object {
        // Матчит https://github.com/{owner}/{repo}/releases/latest/download/<path>
        // и https://github.com/{owner}/{repo}/releases/download/{tag}/<path>.
        // Для таких URL фоновая проверка идёт через GitHub Releases API, а не
        // через HttpFetcher — иначе каждый запрос инкрементит release download
        // counter на GitHub, даже если мы тянем только `.meta.js`.
        // Всё остальное (raw.githubusercontent.com, github.com/.../blob/...,
        // произвольные домены) остаётся на legacy-пути.
        private val GITHUB_RELEASE_ASSET_PATTERN =
            Regex("""^https?://github\.com/([^/]+)/([^/]+)/releases/(?:latest/download|download/[^/]+)/""")
    }
}
