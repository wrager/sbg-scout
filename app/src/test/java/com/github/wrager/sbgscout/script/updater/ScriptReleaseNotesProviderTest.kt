package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptVersion
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ScriptReleaseNotesProviderTest {
    private lateinit var githubReleaseProvider: GithubReleaseProvider
    private lateinit var provider: ScriptReleaseNotesProvider

    @Before
    fun setUp() {
        githubReleaseProvider = mockk()
        provider = ScriptReleaseNotesProvider(githubReleaseProvider)
    }

    @Test
    fun `returns null for non-github url`() =
        runTest {
            val result =
                provider.fetchReleaseNotes(
                    "https://example.com/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertNull(result)
        }

    @Test
    fun `returns null when no releases are newer`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.0.0", "Bug fixes"),
                    release("v0.9.0", "Initial"),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertNull(result)
        }

    @Test
    fun `aggregates notes for releases newer than current`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.2.0", "Added feature C"),
                    release("v1.1.0", "Added feature B"),
                    release("v1.0.0", "Added feature A"),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertEquals("v1.2.0\nAdded feature C\n\nv1.1.0\nAdded feature B", result)
        }

    @Test
    fun `skips releases with empty body`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.2.0", "Notes for 1.2"),
                    release("v1.1.0", ""),
                    release("v1.0.0", "Notes for 1.0"),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertEquals("v1.2.0\nNotes for 1.2", result)
        }

    @Test
    fun `skips releases with null body`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.1.0", null),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertNull(result)
        }

    @Test
    fun `returns null when all newer releases have empty bodies`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.2.0", ""),
                    release("v1.1.0", null),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertNull(result)
        }

    @Test
    fun `skips tags that are not semantic versions`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.1.0", "Real release"),
                    release("beta-2", "Beta notes"),
                    release("v1.0.0", "Old"),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertEquals("v1.1.0\nReal release", result)
        }

    @Test
    fun `trims whitespace from body`() =
        runTest {
            coEvery { githubReleaseProvider.fetchReleases("owner", "repo") } returns
                listOf(
                    release("v1.1.0", "  Notes with whitespace  \n"),
                )
            val result =
                provider.fetchReleaseNotes(
                    "https://github.com/owner/repo/releases/latest/download/script.user.js",
                    ScriptVersion("1.0.0"),
                )
            assertEquals("v1.1.0\nNotes with whitespace", result)
        }

    @Test
    fun `aggregateNotes static method works independently`() {
        val releases =
            listOf(
                release("v2.0.0", "Major update"),
                release("v1.5.0", "Minor update"),
                release("v1.0.0", "Initial"),
            )
        val result =
            ScriptReleaseNotesProvider.aggregateNotes(
                releases,
                ScriptVersion("1.0.0"),
            )
        assertEquals("v2.0.0\nMajor update\n\nv1.5.0\nMinor update", result)
    }

    @Test
    fun `includes same number stable release notes when current version has a suffix`() {
        val releases =
            listOf(
                release("v1.2.4", "Next release"),
                release("v1.2.3", "Stable release"),
                release("v1.2.2", "Older release"),
            )

        val result = ScriptReleaseNotesProvider.aggregateNotes(releases, ScriptVersion("1.2.3-custom.42"))

        assertEquals("v1.2.4\nNext release\n\nv1.2.3\nStable release", result)
    }

    private fun release(
        tagName: String,
        body: String?,
    ): GithubRelease =
        GithubRelease(
            tagName = tagName,
            assets = emptyList(),
            body = body,
        )
}
