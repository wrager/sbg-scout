package com.github.wrager.sbguserscripts.script.updater

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubReleaseProviderTest {

    @Test
    fun `extracts owner and repository from GitHub release URL`() {
        val result = GithubReleaseProvider.extractOwnerAndRepository(
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
        )
        assertEquals("wrager" to "sbg-vanilla-plus", result)
    }

    @Test
    fun `extracts owner and repository from GitHub raw URL`() {
        val result = GithubReleaseProvider.extractOwnerAndRepository(
            "https://github.com/anmiles/userscripts/raw/refs/heads/main/public/sbg.plus.user.js",
        )
        assertEquals("anmiles" to "userscripts", result)
    }

    @Test
    fun `returns null for non-GitHub URL`() {
        val result = GithubReleaseProvider.extractOwnerAndRepository(
            "https://anmiles.net/userscripts/sbg.plus.user.js",
        )
        assertNull(result)
    }

    @Test
    fun `returns null for empty URL`() {
        assertNull(GithubReleaseProvider.extractOwnerAndRepository(""))
    }

    @Test
    fun `parses releases JSON correctly`() {
        val releases = GithubReleaseProvider.parseReleases(RELEASES_JSON)

        assertEquals(2, releases.size)
        assertEquals("v2.0.0", releases[0].tagName)
        assertEquals(1, releases[0].assets.size)
        assertEquals("script.user.js", releases[0].assets[0].name)
        assertEquals(
            "https://github.com/owner/repo/releases/download/v2.0.0/script.user.js",
            releases[0].assets[0].downloadUrl,
        )
        assertEquals("v1.0.0", releases[1].tagName)
    }

    @Test
    fun `filters out prereleases`() {
        val releases = GithubReleaseProvider.parseReleases(RELEASES_WITH_PRERELEASE_JSON)

        assertEquals(1, releases.size)
        assertEquals("v1.0.0", releases[0].tagName)
    }

    @Test
    fun `parses release with no assets`() {
        val releases = GithubReleaseProvider.parseReleases(RELEASE_NO_ASSETS_JSON)

        assertEquals(1, releases.size)
        assertEquals(emptyList<GithubAsset>(), releases[0].assets)
    }

    @Test
    fun `parses empty releases array`() {
        val releases = GithubReleaseProvider.parseReleases("[]")

        assertEquals(emptyList<GithubRelease>(), releases)
    }

    @Test
    fun `fetchReleases calls API with correct URL and headers`() = runTest {
        val httpFetcher = mockk<HttpFetcher>()
        coEvery {
            httpFetcher.fetch(
                "https://api.github.com/repos/wrager/sbg-vanilla-plus/releases",
                mapOf("Accept" to "application/vnd.github+json"),
            )
        } returns "[]"

        val provider = GithubReleaseProvider(httpFetcher)
        val releases = provider.fetchReleases("wrager", "sbg-vanilla-plus")

        assertEquals(emptyList<GithubRelease>(), releases)
    }

    companion object {
        private val RELEASES_JSON = """
            [
                {
                    "tag_name": "v2.0.0",
                    "prerelease": false,
                    "assets": [
                        {
                            "name": "script.user.js",
                            "browser_download_url": "https://github.com/owner/repo/releases/download/v2.0.0/script.user.js"
                        }
                    ]
                },
                {
                    "tag_name": "v1.0.0",
                    "prerelease": false,
                    "assets": [
                        {
                            "name": "script.user.js",
                            "browser_download_url": "https://github.com/owner/repo/releases/download/v1.0.0/script.user.js"
                        }
                    ]
                }
            ]
        """.trimIndent()

        private val RELEASES_WITH_PRERELEASE_JSON = """
            [
                {
                    "tag_name": "v2.0.0-beta",
                    "prerelease": true,
                    "assets": []
                },
                {
                    "tag_name": "v1.0.0",
                    "prerelease": false,
                    "assets": []
                }
            ]
        """.trimIndent()

        private val RELEASE_NO_ASSETS_JSON = """
            [
                {
                    "tag_name": "v1.0.0",
                    "prerelease": false,
                    "assets": []
                }
            ]
        """.trimIndent()
    }
}
