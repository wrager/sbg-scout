package com.github.wrager.sbguserscripts.script.updater

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import com.github.wrager.sbguserscripts.script.parser.HeaderParser
import com.github.wrager.sbguserscripts.script.storage.ScriptStorage
import kotlin.coroutines.cancellation.CancellationException

class ScriptDownloader(
    private val httpFetcher: HttpFetcher,
    private val scriptStorage: ScriptStorage,
) {
    suspend fun download(
        url: String,
        isPreset: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): ScriptDownloadResult {
        return try {
            val content = httpFetcher.fetch(url, onProgress = onProgress)
            val header = HeaderParser.parse(content)
                ?: return ScriptDownloadResult.Failure(
                    url,
                    IllegalStateException("No UserScript header found"),
                )

            val identifier = buildIdentifier(header.namespace, header.name)

            val script = UserScript(
                identifier = identifier,
                header = header,
                sourceUrl = header.downloadUrl ?: url,
                updateUrl = header.updateUrl ?: header.downloadUrl ?: url,
                content = content,
                enabled = false,
                isPreset = isPreset,
            )

            scriptStorage.save(script)
            ScriptDownloadResult.Success(script)
        } catch (exception: CancellationException) {
            throw exception
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            ScriptDownloadResult.Failure(url, exception)
        }
    }

    private fun buildIdentifier(namespace: String?, name: String): ScriptIdentifier {
        val prefix = namespace
            ?.removePrefix("https://")
            ?.removePrefix("http://")
        return if (prefix != null) {
            ScriptIdentifier("$prefix/$name")
        } else {
            ScriptIdentifier(name)
        }
    }
}
