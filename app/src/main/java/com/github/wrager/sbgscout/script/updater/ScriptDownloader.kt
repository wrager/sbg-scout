package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.installer.ScriptInstallResult
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import kotlin.coroutines.cancellation.CancellationException

class ScriptDownloader(
    private val httpFetcher: HttpFetcher,
    private val scriptInstaller: ScriptInstaller,
) {
    suspend fun download(
        url: String,
        isPreset: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): ScriptDownloadResult =
        try {
            val content = httpFetcher.fetch(url, onProgress = onProgress)
            val parseResult = scriptInstaller.parse(content)

            when (parseResult) {
                is ScriptInstallResult.InvalidHeader ->
                    ScriptDownloadResult.Failure(
                        url,
                        IllegalStateException("No UserScript header found"),
                    )
                is ScriptInstallResult.Parsed -> {
                    val script =
                        parseResult.script.copy(
                            sourceUrl = parseResult.script.header.downloadUrl ?: url,
                            updateUrl =
                                parseResult.script.header.updateUrl
                                    ?: parseResult.script.header.downloadUrl
                                    ?: url,
                            isPreset = isPreset,
                        )
                    scriptInstaller.save(script)
                    ScriptDownloadResult.Success(script)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (
            @Suppress("TooGenericExceptionCaught") exception: Exception,
        ) {
            ScriptDownloadResult.Failure(url, exception)
        }
}
