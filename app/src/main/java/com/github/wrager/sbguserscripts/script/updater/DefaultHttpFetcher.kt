package com.github.wrager.sbguserscripts.script.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class DefaultHttpFetcher : HttpFetcher {

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.useCaches = false
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
