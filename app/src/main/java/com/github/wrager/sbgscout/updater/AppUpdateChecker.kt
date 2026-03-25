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
            AppUpdateResult.UpdateAvailable(latest.tagName, apkAsset.downloadUrl)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            AppUpdateResult.CheckFailed(exception)
        }
    }

    companion object {
        private const val OWNER = "wrager"
        private const val REPOSITORY = "sbg-scout"
    }
}
