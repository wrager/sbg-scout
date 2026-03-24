package com.github.wrager.sbguserscripts.script.updater

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DefaultHttpFetcher : HttpFetcher {

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        onProgress: ((Int) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val contentLength = connection.contentLengthLong
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK_SIZE)
            var totalBytesRead = 0L
            var lastReportedProgress = -1
            connection.inputStream.use { inputStream ->
                var bytesThisRead: Int
                while (inputStream.read(buffer).also { bytesThisRead = it } != -1) {
                    ensureActive()
                    outputStream.write(buffer, 0, bytesThisRead)
                    totalBytesRead += bytesThisRead
                    if (onProgress != null && contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            .coerceIn(0, 100)
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            onProgress(progress)
                        }
                    }
                }
            }
            outputStream.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CHUNK_SIZE = 8192
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
