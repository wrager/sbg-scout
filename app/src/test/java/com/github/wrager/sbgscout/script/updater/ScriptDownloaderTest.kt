package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.installer.ScriptInstallResult
import com.github.wrager.sbgscout.script.installer.ScriptInstaller
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.parser.HeaderParser
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ScriptDownloaderTest {
    private lateinit var httpFetcher: HttpFetcher
    private lateinit var scriptInstaller: ScriptInstaller
    private lateinit var downloader: ScriptDownloader

    @Before
    fun setUp() {
        httpFetcher = mockk()
        scriptInstaller = mockk()
        downloader = ScriptDownloader(httpFetcher, scriptInstaller)
    }

    @Test
    fun `downloads and saves script with valid header`() =
        runTest {
            coEvery { httpFetcher.fetch(any(), any(), any()) } returns SCRIPT_WITH_HEADER
            every { scriptInstaller.parse(SCRIPT_WITH_HEADER) } returns parsedResult(SCRIPT_WITH_HEADER)
            every { scriptInstaller.save(any()) } just Runs

            val result = downloader.download("https://example.com/script.user.js")

            assertTrue(result is ScriptDownloadResult.Success)
            val script = (result as ScriptDownloadResult.Success).script
            assertEquals("Test Script", script.header.name)
            assertEquals("1.0.0", script.header.version)
            verify { scriptInstaller.save(any()) }
        }

    @Test
    fun `calls onProgress callback when provided`() =
        runTest {
            val reportedProgress = mutableListOf<Int>()
            coEvery { httpFetcher.fetch(any(), any(), any()) } answers {
                @Suppress("UNCHECKED_CAST")
                val onProgress = arg<((Int) -> Unit)?>(2)
                onProgress?.invoke(50)
                onProgress?.invoke(100)
                SCRIPT_WITH_HEADER
            }
            every { scriptInstaller.parse(SCRIPT_WITH_HEADER) } returns parsedResult(SCRIPT_WITH_HEADER)
            every { scriptInstaller.save(any()) } just Runs

            downloader.download("https://example.com/script.user.js") { progress ->
                reportedProgress.add(progress)
            }

            assertEquals(listOf(50, 100), reportedProgress)
        }

    @Test
    fun `returns failure when header is missing`() =
        runTest {
            val noHeader = "console.log('no header')"
            coEvery { httpFetcher.fetch(any(), any(), any()) } returns noHeader
            every { scriptInstaller.parse(noHeader) } returns ScriptInstallResult.InvalidHeader

            val result = downloader.download("https://example.com/script.js")

            assertTrue(result is ScriptDownloadResult.Failure)
            assertEquals("https://example.com/script.js", (result as ScriptDownloadResult.Failure).url)
        }

    @Test
    fun `returns failure when network error occurs`() =
        runTest {
            coEvery { httpFetcher.fetch(any(), any(), any()) } throws IOException("Network error")

            val result = downloader.download("https://example.com/script.user.js")

            assertTrue(result is ScriptDownloadResult.Failure)
        }

    @Test
    fun `sets isPreset flag correctly`() =
        runTest {
            coEvery { httpFetcher.fetch(any(), any(), any()) } returns SCRIPT_WITH_HEADER
            every { scriptInstaller.parse(SCRIPT_WITH_HEADER) } returns parsedResult(SCRIPT_WITH_HEADER)
            every { scriptInstaller.save(any()) } just Runs

            val result = downloader.download("https://example.com/script.user.js", isPreset = true)

            assertTrue(result is ScriptDownloadResult.Success)
            assertTrue((result as ScriptDownloadResult.Success).script.isPreset)
        }

    @Test
    fun `derives identifier from namespace and name`() =
        runTest {
            coEvery { httpFetcher.fetch(any()) } returns SCRIPT_WITH_NAMESPACE
            every { scriptInstaller.parse(SCRIPT_WITH_NAMESPACE) } returns
                parsedResult(
                    SCRIPT_WITH_NAMESPACE,
                )
            every { scriptInstaller.save(any()) } just Runs

            val result = downloader.download("https://example.com/script.user.js")

            assertTrue(result is ScriptDownloadResult.Success)
            assertEquals(
                "github.com/test/repo/Test Script",
                (result as ScriptDownloadResult.Success).script.identifier.value,
            )
        }

    @Test
    fun `uses download URL from header as sourceUrl`() =
        runTest {
            coEvery { httpFetcher.fetch(any()) } returns SCRIPT_WITH_DOWNLOAD_URL
            every { scriptInstaller.parse(SCRIPT_WITH_DOWNLOAD_URL) } returns
                parsedResult(
                    SCRIPT_WITH_DOWNLOAD_URL,
                )
            every { scriptInstaller.save(any()) } just Runs

            val result = downloader.download("https://example.com/script.user.js")

            assertTrue(result is ScriptDownloadResult.Success)
            assertEquals(
                "https://example.com/download.user.js",
                (result as ScriptDownloadResult.Success).script.sourceUrl,
            )
        }

    @Test
    fun `uses provided URL as fallback when header has no downloadUrl`() =
        runTest {
            coEvery { httpFetcher.fetch(any(), any(), any()) } returns SCRIPT_WITH_HEADER
            every { scriptInstaller.parse(SCRIPT_WITH_HEADER) } returns parsedResult(SCRIPT_WITH_HEADER)
            every { scriptInstaller.save(any()) } just Runs

            val result = downloader.download("https://fallback.com/script.user.js")

            assertTrue(result is ScriptDownloadResult.Success)
            assertEquals(
                "https://fallback.com/script.user.js",
                (result as ScriptDownloadResult.Success).script.sourceUrl,
            )
        }

    @Test
    fun `new script is disabled by default`() =
        runTest {
            coEvery { httpFetcher.fetch(any(), any(), any()) } returns SCRIPT_WITH_HEADER
            every { scriptInstaller.parse(SCRIPT_WITH_HEADER) } returns parsedResult(SCRIPT_WITH_HEADER)
            every { scriptInstaller.save(any()) } just Runs

            val result = downloader.download("https://example.com/script.user.js")

            assertTrue(result is ScriptDownloadResult.Success)
            assertFalse((result as ScriptDownloadResult.Success).script.enabled)
        }

    @Test
    fun `saves augmented script with URL fallbacks`() =
        runTest {
            coEvery { httpFetcher.fetch(any(), any(), any()) } returns SCRIPT_WITH_HEADER
            every { scriptInstaller.parse(SCRIPT_WITH_HEADER) } returns parsedResult(SCRIPT_WITH_HEADER)
            val saved = slot<UserScript>()
            every { scriptInstaller.save(capture(saved)) } just Runs

            downloader.download("https://fallback.com/script.user.js", isPreset = true)

            assertEquals("https://fallback.com/script.user.js", saved.captured.sourceUrl)
            assertEquals("https://fallback.com/script.user.js", saved.captured.updateUrl)
            assertTrue(saved.captured.isPreset)
        }

    companion object {
        private val SCRIPT_WITH_HEADER =
            """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // ==/UserScript==
            console.log('test');
            """.trimIndent()

        private val SCRIPT_WITH_NAMESPACE =
            """
            // ==UserScript==
            // @name Test Script
            // @namespace https://github.com/test/repo
            // @version 1.0.0
            // ==/UserScript==
            """.trimIndent()

        private val SCRIPT_WITH_DOWNLOAD_URL =
            """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // @downloadURL https://example.com/download.user.js
            // ==/UserScript==
            """.trimIndent()

        /**
         * Парсит контент через реальный [HeaderParser] и строит
         * идентификатор — имитация поведения [ScriptInstaller.parse].
         */
        private fun parsedResult(content: String): ScriptInstallResult.Parsed {
            val header = HeaderParser.parse(content)!!
            val namespace =
                header.namespace
                    ?.removePrefix("https://")
                    ?.removePrefix("http://")
            val identifier =
                if (namespace != null) {
                    ScriptIdentifier("$namespace/${header.name}")
                } else {
                    ScriptIdentifier(header.name)
                }
            return ScriptInstallResult.Parsed(
                UserScript(
                    identifier = identifier,
                    header = header,
                    sourceUrl = header.downloadUrl,
                    updateUrl = header.updateUrl ?: header.downloadUrl,
                    content = content,
                ),
            )
        }
    }
}
