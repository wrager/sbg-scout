package com.github.wrager.sbgscout.script.updater

interface HttpFetcher {
    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Int) -> Unit)? = null,
    ): String

    /**
     * Скачивает файл по [url] и сохраняет в [destination].
     * В отличие от [fetch], работает с бинарными данными.
     */
    suspend fun fetchToFile(
        url: String,
        destination: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Int) -> Unit)? = null,
    )
}
