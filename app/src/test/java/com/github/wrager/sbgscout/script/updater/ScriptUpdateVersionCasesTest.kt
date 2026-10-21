package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScriptUpdateVersionCasesTest(
    private val currentVersion: String,
    private val releaseVersion: String,
    private val updateAvailable: Boolean,
) {
    private val identifier = ScriptIdentifier("test/script")
    private val httpFetcher = mockk<HttpFetcher>()
    private val githubReleaseProvider = mockk<GithubReleaseProvider>()
    private val checker = ScriptUpdateChecker(httpFetcher, mockk<ScriptStorage>(), githubReleaseProvider)

    @Test
    fun `checks stable release version from github`() =
        runTest {
            val updateUrl = "https://github.com/owner/repo/releases/latest/download/script.meta.js"
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(GithubRelease(tagName = "v$releaseVersion", assets = emptyList()))
            // При более старом теге checker проверяет @version через mono-repo fallback.
            coEvery { httpFetcher.fetch(updateUrl) } returns releaseMetadata()

            assertEquals(expectedResult(), checker.checkForUpdate(script(updateUrl)))

            coVerify(exactly = 1) { githubReleaseProvider.fetchReleases("owner", "repo") }
            if (updateAvailable) {
                coVerify(exactly = 0) { httpFetcher.fetch(any()) }
            }
        }

    @Test
    fun `checks stable release version from metadata`() =
        runTest {
            val updateUrl = "https://example.com/script.meta.js"
            coEvery { httpFetcher.fetch(updateUrl) } returns releaseMetadata()

            assertEquals(expectedResult(), checker.checkForUpdate(script(updateUrl)))

            coVerify(exactly = 0) { githubReleaseProvider.fetchReleases(any(), any()) }
        }

    private fun expectedResult(): ScriptUpdateResult =
        if (updateAvailable) {
            ScriptUpdateResult.UpdateAvailable(identifier, ScriptVersion(currentVersion), ScriptVersion(releaseVersion))
        } else {
            ScriptUpdateResult.UpToDate(identifier)
        }

    private fun script(updateUrl: String) =
        UserScript(
            identifier = identifier,
            header = ScriptHeader(name = "Test Script", version = currentVersion),
            sourceUrl = "https://example.com/script.user.js",
            updateUrl = updateUrl,
            content = "code",
            enabled = true,
        )

    private fun releaseMetadata() =
        """
        // ==UserScript==
        // @name Test Script
        // @version $releaseVersion
        // ==/UserScript==
        """.trimIndent()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}: update={2}")
        fun versions(): List<Array<Any>> =
            listOf(
                arrayOf("1.2.3", "1.2.3", false),
                arrayOf("1.2.3", "1.2.4", true),
                arrayOf("1.9.9", "1.10.0", true),
                arrayOf("1.99.99", "2.0.0", true),
                arrayOf("1.2.3-beta", "1.2.3", true),
                arrayOf("1.2.3-beta.2", "1.2.3", true),
                arrayOf("1.2.3-custom.42", "1.2.3", true),
                arrayOf("1.2.3-debug", "1.2.3", true),
                arrayOf("1.2.3-instr", "1.2.3", true),
                arrayOf("1.2.3preview", "1.2.3", true),
                arrayOf("1.2.3+build.42", "1.2.3", true),
                arrayOf("1.2.3-beta.99", "1.2.4", true),
                arrayOf("1.2.4-beta", "1.2.3", false),
                arrayOf("1.3.0-custom", "1.2.99", false),
                arrayOf("2.0.0-beta", "1.99.99", false),
                arrayOf("1.2.4", "1.2.3", false),
                arrayOf("1.2", "1.2.0", false),
                arrayOf("1.2-beta.9", "1.2.0", true),
                arrayOf("1.2.3.1", "1.2.3.2", true),
                arrayOf("1.2.3.1-custom.7", "1.2.3.1", true),
            )
    }
}
