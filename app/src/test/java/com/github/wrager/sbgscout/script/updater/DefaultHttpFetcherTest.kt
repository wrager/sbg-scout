package com.github.wrager.sbgscout.script.updater

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-тесты [DefaultHttpFetcher] на локальном [MockWebServer].
 *
 * Покрывают обе suspend-функции ([fetch], [fetchToFile]) и все ветки
 * progress reporting (с Content-Length и без), error handling, urlRewriter.
 */
class DefaultHttpFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: DefaultHttpFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = DefaultHttpFetcher()
    }

    @After
    fun tearDown() {
        server.shutdown()
        DefaultHttpFetcher.urlRewriter = null
    }

    @Test
    fun `fetch returns body for 200 response`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello world"))

        val body = fetcher.fetch(server.url("/x").toString())
        assertEquals("hello world", body)
    }

    @Test
    fun `fetch sets custom headers from parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))

        fetcher.fetch(
            server.url("/x").toString(),
            headers = mapOf("X-Custom" to "value", "Accept" to "application/json"),
        )

        val request = server.takeRequest()
        assertEquals("value", request.getHeader("X-Custom"))
        assertEquals("application/json", request.getHeader("Accept"))
        // Cache busters из DefaultHttpFetcher
        assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
        assertEquals("no-cache", request.getHeader("Pragma"))
    }

    @Test
    fun `fetch reports progress when Content-Length is known`() = runBlocking {
        val body = "x".repeat(16_384) // 2 chunks of 8192
        server.enqueue(
            MockResponse()
                .setBody(body)
                .setHeader("Content-Length", body.length.toString()),
        )

        val progressReports = mutableListOf<Int>()
        fetcher.fetch(server.url("/x").toString(), onProgress = { progressReports += it })

        assertTrue("progress должен содержать >0 значение", progressReports.any { it > 0 })
        assertEquals(100, progressReports.last())
    }

    @Test
    fun `fetch reports progress as 0 when Content-Length is unknown`() = runBlocking {
        // MockWebServer с chunked transfer encoding — Content-Length не известен заранее.
        // Покрывает ветку `contentLength > 0 = false → 0`.
        val buffer = Buffer().writeUtf8("chunk body data")
        server.enqueue(
            MockResponse()
                .setBody(buffer)
                .setChunkedBody("chunk body data", 4),
        )

        val progressReports = mutableListOf<Int>()
        fetcher.fetch(server.url("/x").toString(), onProgress = { progressReports += it })

        assertTrue(
            "progress должен содержать 0 при unknown content-length, было=$progressReports",
            progressReports.any { it == 0 },
        )
    }

    @Test
    fun `fetch works without progress callback`() = runBlocking {
        // Покрывает ветку `onProgress != null` = false.
        server.enqueue(MockResponse().setBody("no-progress-cb"))

        val body = fetcher.fetch(server.url("/x").toString(), onProgress = null)
        assertEquals("no-progress-cb", body)
    }

    @Test
    fun `fetch throws on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val exception = runCatching { fetcher.fetch(server.url("/x").toString()) }.exceptionOrNull()
        assertTrue(exception is IOException)
    }

    @Test
    fun `fetch applies urlRewriter when set`() = runBlocking {
        val fakeUrl = "https://github.com/owner/repo/releases"
        val realUrl = server.url("/gh-web/owner/repo/releases").toString()
        DefaultHttpFetcher.urlRewriter = { url -> if (url == fakeUrl) realUrl else url }
        server.enqueue(MockResponse().setBody("rewritten"))

        val body = fetcher.fetch(fakeUrl)
        assertEquals("rewritten", body)
    }

    @Test
    fun `fetchToFile writes response body to file with progress reports`() = runBlocking {
        val body = "file content body"
        server.enqueue(
            MockResponse()
                .setBody(body)
                .setHeader("Content-Length", body.length.toString()),
        )

        val destination = File.createTempFile("fetcher-test", ".bin").apply { deleteOnExit() }
        val progressReports = mutableListOf<Int>()
        fetcher.fetchToFile(
            server.url("/x").toString(),
            destination,
            onProgress = { progressReports += it },
        )

        assertEquals(body, destination.readText())
        assertEquals(100, progressReports.last())
    }

    @Test
    fun `fetchToFile creates parent directory when missing`() = runBlocking {
        server.enqueue(MockResponse().setBody("parent-dir"))

        val tempRoot = File.createTempFile("fetcher-parent", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val destination = File(tempRoot, "nested/dir/file.bin")
        try {
            fetcher.fetchToFile(server.url("/x").toString(), destination)

            assertEquals("parent-dir", destination.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `fetchToFile works without progress callback and with chunked transfer`() = runBlocking {
        server.enqueue(
            MockResponse().setChunkedBody("chunked-file-content", 4),
        )
        val destination = File.createTempFile("fetcher-chunked", ".bin").apply { deleteOnExit() }

        fetcher.fetchToFile(server.url("/x").toString(), destination, onProgress = null)

        assertEquals("chunked-file-content", destination.readText())
    }

    @Test
    fun `fetchToFile reports only distinct progress values`() = runBlocking {
        // Покрывает ветку `if (progress != lastReportedProgress)` = false
        // (одинаковый progress — не дублируется). Для этого используем chunked
        // transfer, где computeProgress всегда = 0 (contentLength=-1) — второй
        // чанк не триггерит повторный callback с тем же значением.
        server.enqueue(MockResponse().setChunkedBody("chunk-a-chunk-b-chunk-c", 6))
        val destination = File.createTempFile("fetcher-distinct", ".bin").apply { deleteOnExit() }

        val progressReports = mutableListOf<Int>()
        fetcher.fetchToFile(
            server.url("/x").toString(),
            destination,
            onProgress = { progressReports += it },
        )

        // Все значения должны быть 0 (contentLength unknown) и дубликаты отфильтрованы.
        assertEquals("chunk-a-chunk-b-chunk-c", destination.readText())
        assertEquals(
            "При одинаковом progress должен быть только первый отчёт: $progressReports",
            1,
            progressReports.size,
        )
        assertEquals(0, progressReports[0])
    }

    @Test
    fun `fetch reports distinct progress values only`() = runBlocking {
        // Покрывает ветку `if (progress != lastReportedProgress)` = false в fetch().
        server.enqueue(MockResponse().setChunkedBody("a-a-a-a-a-a-a", 2))

        val progressReports = mutableListOf<Int>()
        fetcher.fetch(server.url("/x").toString(), onProgress = { progressReports += it })

        assertEquals(1, progressReports.size)
    }

    @Test
    fun `fetchToFile handles destination with null parentFile`() {
        runBlocking {
            // Покрывает ветку `destination.parentFile?.mkdirs()` = null —
            // File с parentFile=null возникает у относительного пути без папки.
            server.enqueue(MockResponse().setBody("no-parent"))

            // Относительный File без предков даёт parentFile=null.
            val destination = File("fetcher-no-parent-${System.nanoTime()}.bin")
            destination.deleteOnExit()

            try {
                fetcher.fetchToFile(server.url("/x").toString(), destination)
                assertEquals("no-parent", destination.readText())
            } finally {
                destination.delete()
            }
        }
    }
}
