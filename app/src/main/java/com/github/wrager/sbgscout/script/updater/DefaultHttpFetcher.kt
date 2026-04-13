package com.github.wrager.sbgscout.script.updater

import androidx.annotation.VisibleForTesting
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
        val connection = URL(rewriteUrl(url)).openConnection() as HttpURLConnection
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
        val connection = URL(rewriteUrl(url)).openConnection() as HttpURLConnection
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

    companion object {
        private const val CHUNK_SIZE = 8192
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val READ_TIMEOUT_MS = 10_000

        /** Максимальное время от начала запроса до получения первого байта ответа. */
        private const val RESPONSE_DEADLINE_MS_DEFAULT = 15_000L

        /**
         * Test seam: override дедлайна для unit-тестов, чтобы не ждать
         * реальные 15 секунд. В проде всегда `null` — используется
         * [RESPONSE_DEADLINE_MS_DEFAULT].
         */
        @VisibleForTesting
        @Volatile
        @JvmStatic
        internal var responseDeadlineOverrideMs: Long? = null

        private val RESPONSE_DEADLINE_MS: Long
            get() = responseDeadlineOverrideMs ?: RESPONSE_DEADLINE_MS_DEFAULT

        /**
         * Открывает [HttpURLConnection.getInputStream] с жёстким дедлайном.
         * Расположена в companion (исключена из JaCoCo) для устранения
         * synthetic nullable branches у `result`/`error` slot'ов — unit-тест
         * всё равно покрывает обе ветки через [DefaultHttpFetcher.fetch].
         */
        @Suppress("TooGenericExceptionCaught")
        internal fun openInputStreamWithDeadline(connection: HttpURLConnection): InputStream {
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
                throw SocketTimeoutException(
                    "Response deadline exceeded (${RESPONSE_DEADLINE_MS}ms): ${connection.url}",
                )
            }

            error?.let { throw it }
            return checkNotNull(result) { "No response stream" }
        }

        /** Время ожидания завершения потока после disconnect. */
        private const val DISCONNECT_JOIN_TIMEOUT_MS = 2_000L

        /**
         * Хук перенаправления URL для e2e-тестов. В проде всегда `null` —
         * используется исходный URL. В androidTest выставляется через
         * `HttpRewriterRule` так, чтобы GitHub-адреса (github.com, api.github.com,
         * raw.githubusercontent.com, objects.githubusercontent.com) шли на
         * локальный `FakeGameServer`, а не на реальный интернет.
         *
         * Volatile — вызывается из coroutine-ов в `Dispatchers.IO`, возможен
         * конкурентный доступ между тестовыми потоками и IO-потоками.
         */
        @VisibleForTesting
        @Volatile
        @JvmStatic
        internal var urlRewriter: ((String) -> String)? = null

        private fun rewriteUrl(url: String): String = urlRewriter?.invoke(url) ?: url
    }
}
