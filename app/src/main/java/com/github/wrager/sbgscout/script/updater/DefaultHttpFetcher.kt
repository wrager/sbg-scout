package com.github.wrager.sbgscout.script.updater

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext
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

            // connection.inputStream — blocking: DNS + connect + redirect + первый ответ.
            // connectTimeout не покрывает DNS-резолв на Android, а при redirect
            // (github.com → objects.githubusercontent.com) таймауты удваиваются.
            // Ограничиваем общее время до получения потока данных.
            val inputStream = openInputStreamWithDeadline(connection)

            val contentLength = connection.contentLengthLong
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK_SIZE)
            var totalBytesRead = 0L
            var lastReportedProgress = -1
            inputStream.use {
                var bytesThisRead: Int
                while (inputStream.read(buffer).also { bytesThisRead = it } != -1) {
                    ensureActive()
                    outputStream.write(buffer, 0, bytesThisRead)
                    totalBytesRead += bytesThisRead
                    if (onProgress != null) {
                        val progress = if (contentLength > 0) {
                            ((totalBytesRead * 100) / contentLength).toInt()
                                .coerceIn(0, 100)
                        } else {
                            // Content-Length неизвестен (chunked transfer, redirect) —
                            // сигнализируем 0 %, чтобы вызывающий код узнал,
                            // что соединение установлено и данные пошли
                            0
                        }
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

    override suspend fun fetchToFile(
        url: String,
        destination: File,
        headers: Map<String, String>,
        onProgress: ((Int) -> Unit)?,
    ): Unit = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            val inputStream = openInputStreamWithDeadline(connection)
            val contentLength = connection.contentLengthLong

            destination.parentFile?.mkdirs()
            inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    readStream(input, output, contentLength, onProgress)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun readStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?,
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        var totalBytesRead = 0L
        var lastReportedProgress = -1
        var bytesThisRead: Int
        while (inputStream.read(buffer).also { bytesThisRead = it } != -1) {
            coroutineContext.ensureActive()
            outputStream.write(buffer, 0, bytesThisRead)
            totalBytesRead += bytesThisRead
            if (onProgress != null) {
                val progress = computeProgress(totalBytesRead, contentLength)
                if (progress != lastReportedProgress) {
                    lastReportedProgress = progress
                    onProgress(progress)
                }
            }
        }
    }

    private fun computeProgress(bytesRead: Long, contentLength: Long): Int =
        if (contentLength > 0) {
            ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
        } else {
            0
        }

    /**
     * Открывает [HttpURLConnection.getInputStream] с жёстким дедлайном [RESPONSE_DEADLINE_MS].
     *
     * `connectTimeout` у [HttpURLConnection] не покрывает DNS-резолв на Android,
     * а при redirect таймауты применяются к каждому hop отдельно, суммируясь.
     * Запускаем blocking-вызов в отдельном потоке и прерываем через
     * [HttpURLConnection.disconnect], если дедлайн истёк.
     */
    @Suppress("TooGenericExceptionCaught") // inputStream бросает IOException и подклассы
    private fun openInputStreamWithDeadline(connection: HttpURLConnection): InputStream {
        var result: InputStream? = null
        var error: Exception? = null

        val thread = Thread {
            try {
                result = connection.inputStream
            } catch (exception: Exception) {
                error = exception
            }
        }
        thread.start()
        thread.join(RESPONSE_DEADLINE_MS)

        if (thread.isAlive) {
            // Дедлайн истёк — disconnect прервёт blocking I/O в потоке
            connection.disconnect()
            thread.join(DISCONNECT_JOIN_TIMEOUT_MS)
            error = SocketTimeoutException(
                "Response deadline exceeded (${RESPONSE_DEADLINE_MS}ms): ${connection.url}",
            )
        }

        error?.let { throw it }
        return checkNotNull(result) { "No response stream" }
    }

    companion object {
        private const val CHUNK_SIZE = 8192
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val READ_TIMEOUT_MS = 10_000

        /** Максимальное время от начала запроса до получения первого байта ответа. */
        private const val RESPONSE_DEADLINE_MS = 15_000L

        /** Время ожидания завершения потока после disconnect. */
        private const val DISCONNECT_JOIN_TIMEOUT_MS = 2_000L
    }
}
