package com.github.wrager.sbgscout.updater

import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider

/**
 * Проверяет наличие обновлений приложения через GitHub Releases API.
 *
 * Сравнивает tag_name последнего релиза (без префикса "v") с [currentVersion]
 * через [ScriptVersion] (семантическое сравнение).
 */
class AppUpdateChecker(
    private val githubReleaseProvider: GithubReleaseProvider,
    private val currentVersion: String,
) {

    suspend fun check(): AppUpdateResult {
        return try {
            val releases = githubReleaseProvider.fetchReleases(OWNER, REPOSITORY)
            val latest = releases.firstOrNull()
                ?: return AppUpdateResult.UpToDate
            val latestVersion = ScriptVersion(latest.tagName.removePrefix("v"))
            val current = ScriptVersion(currentVersion)
            if (latestVersion <= current) return AppUpdateResult.UpToDate
            val apkAsset = latest.assets.find { it.name.endsWith(".apk") }
                ?: return AppUpdateResult.CheckFailed(
                    IllegalStateException("No APK asset in release ${latest.tagName}"),
                )
            // Собираем release notes всех версий между текущей и последней
            val releaseNotes = buildAggregatedReleaseNotes(releases, current)
            AppUpdateResult.UpdateAvailable(latest.tagName, apkAsset.downloadUrl, releaseNotes)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            AppUpdateResult.CheckFailed(exception)
        }
    }

    /**
     * Объединяет release notes всех релизов новее [currentVersion].
     *
     * GitHub API возвращает релизы в обратном хронологическом порядке,
     * поэтому итоговый текст идёт от новейшего к старейшему.
     */
    private fun buildAggregatedReleaseNotes(
        releases: List<com.github.wrager.sbgscout.script.updater.GithubRelease>,
        currentVersion: ScriptVersion,
    ): String? {
        val notes = releases
            .filter { ScriptVersion(it.tagName.removePrefix("v")) > currentVersion }
            .mapNotNull { release ->
                val body = release.body?.trim()
                if (body.isNullOrEmpty()) null else "${release.tagName}\n$body"
            }
        return notes.joinToString("\n\n").ifEmpty { null }
    }

    companion object {
        private const val OWNER = "wrager"
        private const val REPOSITORY = "sbg-scout"
    }
}
