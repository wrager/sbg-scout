package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.UserScript

sealed class ScriptDownloadResult {
    data class Success(
        val script: UserScript,
    ) : ScriptDownloadResult()

    data class Failure(
        val url: String,
        val error: Throwable,
    ) : ScriptDownloadResult()
}
