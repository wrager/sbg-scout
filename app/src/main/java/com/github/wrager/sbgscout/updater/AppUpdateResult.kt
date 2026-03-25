package com.github.wrager.sbgscout.updater

sealed class AppUpdateResult {
    data class UpdateAvailable(
        val tagName: String,
        val downloadUrl: String,
        val releaseNotes: String?,
    ) : AppUpdateResult()

    data object UpToDate : AppUpdateResult()

    data class CheckFailed(val error: Throwable) : AppUpdateResult()
}
