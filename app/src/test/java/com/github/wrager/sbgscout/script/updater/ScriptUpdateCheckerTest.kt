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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ScriptUpdateCheckerTest {

    private lateinit var httpFetcher: HttpFetcher
    private lateinit var scriptStorage: ScriptStorage
    private lateinit var githubReleaseProvider: GithubReleaseProvider
    private lateinit var checker: ScriptUpdateChecker

    private val testIdentifier = ScriptIdentifier("test/script")

    @Before
    fun setUp() {
        httpFetcher = mockk()
        scriptStorage = mockk()
        githubReleaseProvider = mockk()
        checker = ScriptUpdateChecker(httpFetcher, scriptStorage, githubReleaseProvider)
    }

    private fun createScript(
        version: String? = "1.0.0",
        updateUrl: String? = "https://example.com/script.meta.js",
    ): UserScript = UserScript(
        identifier = testIdentifier,
        header = ScriptHeader(name = "Test Script", version = version),
        sourceUrl = "https://example.com/script.user.js",
        updateUrl = updateUrl,
        content = "code",
        enabled = true,
    )

    @Test
    fun `returns UpdateAvailable when remote version is newer`() = runTest {
        val script = createScript(version = "1.0.0")
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        val updateResult = result as ScriptUpdateResult.UpdateAvailable
        assertEquals(ScriptVersion("1.0.0"), updateResult.currentVersion)
        assertEquals(ScriptVersion("2.0.0"), updateResult.latestVersion)
    }

    @Test
    fun `returns UpToDate when versions are equal`() = runTest {
        val script = createScript(version = "1.0.0")
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_1

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
    }

    @Test
    fun `returns UpToDate when current version is newer`() = runTest {
        val script = createScript(version = "3.0.0")
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
    }

    @Test
    fun `returns CheckFailed when no updateUrl configured`() = runTest {
        val script = createScript(updateUrl = null)

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when network error occurs`() = runTest {
        val script = createScript()
        coEvery { httpFetcher.fetch(any()) } throws IOException("Network error")

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when remote header cannot be parsed`() = runTest {
        val script = createScript()
        coEvery { httpFetcher.fetch(any()) } returns "not a valid header"

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when current script has no version`() = runTest {
        val script = createScript(version = null)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `returns CheckFailed when remote script has no version`() = runTest {
        val script = createScript()
        coEvery { httpFetcher.fetch(any()) } returns META_NO_VERSION

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `checkAllForUpdates checks all scripts from storage`() = runTest {
        val script1 = createScript(version = "1.0.0")
        val script2 = createScript(version = "2.0.0").copy(
            identifier = ScriptIdentifier("test/script2"),
        )
        coEvery { scriptStorage.getAll() } returns listOf(script1, script2)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val results = checker.checkAllForUpdates()

        assertEquals(2, results.size)
    }

    @Test
    fun `checkAllForUpdates filters out scripts with null updateUrl`() = runTest {
        // Покрывает ветку `.filter { it.updateUrl != null }` = false —
        // скрипт без updateUrl (sideloaded из файла) не должен попасть в
        // результат, иначе checker дёрнет null URL и упадёт.
        val scriptWithUpdate = createScript(version = "1.0.0")
        val scriptWithoutUpdate = createScript(version = "1.0.0", updateUrl = null).copy(
            identifier = ScriptIdentifier("test/no-update"),
        )
        coEvery { scriptStorage.getAll() } returns listOf(scriptWithUpdate, scriptWithoutUpdate)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_1

        val results = checker.checkAllForUpdates()

        assertEquals("Без-updateUrl скрипт должен быть отфильтрован", 1, results.size)
        assertEquals(testIdentifier, (results.single() as ScriptUpdateResult.UpToDate).identifier)
    }

    @Test
    fun `github release asset url routes to api and returns UpdateAvailable`() = runTest {
        val script = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("v2.0.0"))

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        val updateResult = result as ScriptUpdateResult.UpdateAvailable
        assertEquals(ScriptVersion("1.0.0"), updateResult.currentVersion)
        assertEquals(ScriptVersion("2.0.0"), updateResult.latestVersion)
        // Регрессионный гвоздь: legacy-путь не должен вызываться для GitHub URL,
        // иначе release download counter продолжит инкрементиться.
        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
    }

    @Test
    fun `github release asset url with equal tag returns UpToDate`() = runTest {
        val script = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("v1.0.0"))

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
    }

    @Test
    fun `github release asset url with older tag triggers mono-repo fallback`() = runTest {
        // tag=v2.0.0, current=3.0.0: тег меньше установленной версии →
        // mono-repo fallback → HttpFetcher скачивает файл и парсит @version.
        val script = createScript(version = "3.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("v2.0.0"))
        coEvery { httpFetcher.fetch(SVP_META_URL) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        // Fallback получил @version 2.0.0 из файла, 2.0.0 < 3.0.0 → UpToDate.
        assertTrue(result is ScriptUpdateResult.UpToDate)
        coVerify(exactly = 1) { httpFetcher.fetch(SVP_META_URL) }
    }

    @Test
    fun `github release asset url with tag without v prefix works`() = runTest {
        val script = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("2.0.0"))

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        assertEquals(
            ScriptVersion("2.0.0"),
            (result as ScriptUpdateResult.UpdateAvailable).latestVersion,
        )
    }

    @Test
    fun `github release asset url with non-semver tag triggers mono-repo fallback`() = runTest {
        // Нестандартный тег "release-2024-01" парсится как version 0 (leadingDigitsAsInt
        // для "release" = 0), что меньше current=1.0.0 → mono-repo fallback на HttpFetcher.
        // Это безопасное поведение: fallback скачает файл и распарсит реальный @version.
        val script = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("release-2024-01"))
        coEvery { httpFetcher.fetch(SVP_META_URL) } returns META_VERSION_1

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
        // Доказательство fallback: httpFetcher вызван, хотя URL — GitHub release.
        coVerify(exactly = 1) { httpFetcher.fetch(SVP_META_URL) }
    }

    @Test
    fun `github release asset url with empty releases returns CheckFailed`() = runTest {
        val script = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns emptyList()

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `github release asset url with github api failure returns CheckFailed`() = runTest {
        val script = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } throws IOException("429 rate limit")

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.CheckFailed)
    }

    @Test
    fun `github pinned tag download url also routes to api`() = runTest {
        val script = createScript(
            version = "0.7.0",
            updateUrl =
                "https://github.com/wrager/sbg-vanilla-plus/releases/download/v0.8.0/sbg-vanilla-plus.meta.js",
        )
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("v0.8.1"))

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
    }

    @Test
    fun `github repo blob url still uses http fetcher`() = runTest {
        // github.com/owner/repo/blob/... — web-ссылка, не release asset, counter
        // не дёргает, regex не должен её ловить, остаётся на legacy-пути.
        val script = createScript(
            version = "1.0.0",
            updateUrl = "https://github.com/wrager/sbg-vanilla-plus/blob/main/script.meta.js",
        )
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        coVerify(exactly = 1) {
            httpFetcher.fetch("https://github.com/wrager/sbg-vanilla-plus/blob/main/script.meta.js")
        }
    }

    @Test
    fun `raw githubusercontent url still uses http fetcher`() = runTest {
        val rawUrl =
            "https://raw.githubusercontent.com/wrager/sbg-vanilla-plus/main/sbg-vanilla-plus.meta.js"
        val script = createScript(version = "1.0.0", updateUrl = rawUrl)
        coEvery { httpFetcher.fetch(any()) } returns META_VERSION_2

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        coVerify(exactly = 1) { httpFetcher.fetch(rawUrl) }
    }

    @Test
    fun `checkAllForUpdates routes github and non-github scripts correctly`() = runTest {
        val githubScript = createScript(version = "1.0.0", updateUrl = SVP_META_URL)
        val plainScript = createScript(
            version = "1.0.0",
            updateUrl = "https://example.com/script.meta.js",
        ).copy(identifier = ScriptIdentifier("test/plain"))
        coEvery { scriptStorage.getAll() } returns listOf(githubScript, plainScript)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("v2.0.0"))
        coEvery { httpFetcher.fetch("https://example.com/script.meta.js") } returns META_VERSION_2

        val results = checker.checkAllForUpdates()

        assertEquals(2, results.size)
        assertTrue(results.all { it is ScriptUpdateResult.UpdateAvailable })
        coVerify(exactly = 1) {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        }
        coVerify(exactly = 1) { httpFetcher.fetch("https://example.com/script.meta.js") }
    }

    @Test
    fun `github tag less than current version falls back to http fetcher`() = runTest {
        // Mono-repo: tag = EUI version (8.2.0), current = CUI version (26.4.1).
        // 8.2.0 < 26.4.1 → тег не соответствует скрипту → fallback на legacy-путь.
        val cuiUpdateUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js"
        val script = createScript(version = "26.4.1", updateUrl = cuiUpdateUrl)
        coEvery {
            githubReleaseProvider.fetchReleases("egorantonov", "sbg-enhanced")
        } returns listOf(release("8.2.0"))
        coEvery { httpFetcher.fetch(cuiUpdateUrl) } returns META_CUI_26_4_1

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
        // Доказательство fallback: httpFetcher.fetch вызван для CUI URL,
        // хотя URL матчит GitHub release pattern.
        coVerify(exactly = 1) { httpFetcher.fetch(cuiUpdateUrl) }
    }

    @Test
    fun `github tag less than current version with newer remote returns UpdateAvailable`() = runTest {
        // Mono-repo fallback: tag 8.2.0 < current 26.4.1 → legacy path → @version 27.0.0 → update.
        val cuiUpdateUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js"
        val script = createScript(version = "26.4.1", updateUrl = cuiUpdateUrl)
        coEvery {
            githubReleaseProvider.fetchReleases("egorantonov", "sbg-enhanced")
        } returns listOf(release("8.2.0"))
        coEvery { httpFetcher.fetch(cuiUpdateUrl) } returns META_CUI_27_0_0

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpdateAvailable)
        val updateResult = result as ScriptUpdateResult.UpdateAvailable
        assertEquals(ScriptVersion("26.4.1"), updateResult.currentVersion)
        assertEquals(ScriptVersion("27.0.0"), updateResult.latestVersion)
    }

    @Test
    fun `github tag equal to current version does not fall back`() = runTest {
        // Не mono-repo: tag = current → UpToDate через API, без fallback.
        val script = createScript(version = "8.2.0", updateUrl = SVP_META_URL)
        coEvery {
            githubReleaseProvider.fetchReleases("wrager", "sbg-vanilla-plus")
        } returns listOf(release("8.2.0"))

        val result = checker.checkForUpdate(script)

        assertTrue(result is ScriptUpdateResult.UpToDate)
        coVerify(exactly = 0) { httpFetcher.fetch(any()) }
    }

    private fun release(tag: String) = GithubRelease(tagName = tag, assets = emptyList())

    companion object {
        private val META_VERSION_1 = """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // ==/UserScript==
        """.trimIndent()

        private val META_VERSION_2 = """
            // ==UserScript==
            // @name Test Script
            // @version 2.0.0
            // ==/UserScript==
        """.trimIndent()

        private val META_NO_VERSION = """
            // ==UserScript==
            // @name Test Script
            // ==/UserScript==
        """.trimIndent()

        private const val SVP_META_URL =
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js"

        private val META_CUI_26_4_1 = """
            // ==UserScript==
            // @name SBG CUI
            // @version 26.4.1
            // ==/UserScript==
        """.trimIndent()

        private val META_CUI_27_0_0 = """
            // ==UserScript==
            // @name SBG CUI
            // @version 27.0.0
            // ==/UserScript==
        """.trimIndent()
    }
}
