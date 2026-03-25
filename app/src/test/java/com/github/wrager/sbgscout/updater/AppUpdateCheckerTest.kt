package com.github.wrager.sbgscout.updater

import com.github.wrager.sbgscout.script.updater.GithubAsset
import com.github.wrager.sbgscout.script.updater.GithubRelease
import com.github.wrager.sbgscout.script.updater.GithubReleaseProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AppUpdateCheckerTest {

    private lateinit var githubReleaseProvider: GithubReleaseProvider
    private lateinit var checker: AppUpdateChecker

    @Before
    fun setUp() {
        githubReleaseProvider = mockk()
        checker = AppUpdateChecker(githubReleaseProvider, CURRENT_VERSION)
    }

    @Test
    fun `returns UpdateAvailable when latest release is newer`() = runTest {
        coEvery { githubReleaseProvider.fetchReleases(any(), any()) } returns listOf(
            release("v1.0.0", "sbg-scout.apk"),
        )
        val result = checker.check()
        assertTrue(result is AppUpdateResult.UpdateAvailable)
        val updateAvailable = result as AppUpdateResult.UpdateAvailable
        assertEquals("v1.0.0", updateAvailable.tagName)
        assertTrue(updateAvailable.downloadUrl.endsWith(".apk"))
    }

    @Test
    fun `returns UpToDate when latest release matches current version`() = runTest {
        coEvery { githubReleaseProvider.fetchReleases(any(), any()) } returns listOf(
            release("v$CURRENT_VERSION", "sbg-scout.apk"),
        )
        val result = checker.check()
        assertEquals(AppUpdateResult.UpToDate, result)
    }

    @Test
    fun `returns UpToDate when latest release is older`() = runTest {
        coEvery { githubReleaseProvider.fetchReleases(any(), any()) } returns listOf(
            release("v0.9.0", "sbg-scout.apk"),
        )
        val result = checker.check()
        assertEquals(AppUpdateResult.UpToDate, result)
    }

    @Test
    fun `returns UpToDate when no releases exist`() = runTest {
        coEvery { githubReleaseProvider.fetchReleases(any(), any()) } returns emptyList()
        val result = checker.check()
        assertEquals(AppUpdateResult.UpToDate, result)
    }

    @Test
    fun `returns CheckFailed when no APK asset in release`() = runTest {
        coEvery { githubReleaseProvider.fetchReleases(any(), any()) } returns listOf(
            GithubRelease(tagName = "v1.0.0", assets = listOf(
                GithubAsset(name = "source.zip", downloadUrl = "https://example.com/source.zip"),
            )),
        )
        val result = checker.check()
        assertTrue(result is AppUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed on network error`() = runTest {
        coEvery { githubReleaseProvider.fetchReleases(any(), any()) } throws IOException("timeout")
        val result = checker.check()
        assertTrue(result is AppUpdateResult.CheckFailed)
        assertEquals("timeout", (result as AppUpdateResult.CheckFailed).error.message)
    }

    private fun release(tagName: String, apkName: String): GithubRelease =
        GithubRelease(
            tagName = tagName,
            assets = listOf(
                GithubAsset(
                    name = apkName,
                    downloadUrl = "https://github.com/wrager/sbg-scout/releases/download/$tagName/$apkName",
                ),
            ),
        )

    companion object {
        private const val CURRENT_VERSION = "0.10.1"
    }
}
