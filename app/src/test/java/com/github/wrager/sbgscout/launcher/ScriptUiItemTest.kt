package com.github.wrager.sbgscout.launcher

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [ScriptUiItem.isGithubHosted] — покрывают все три ветки:
 * null sourceUrl, non-null non-github URL, non-null github URL.
 * Логика через [com.github.wrager.sbgscout.script.updater.GithubReleaseProvider.extractOwnerAndRepository]
 * (regex `github\.com/([^/]+)/([^/]+)`).
 */
class ScriptUiItemTest {

    @Test
    fun `isGithubHosted returns false when sourceUrl is null`() {
        assertFalse(buildItem(sourceUrl = null).isGithubHosted)
    }

    @Test
    fun `isGithubHosted returns false when sourceUrl does not contain github`() {
        assertFalse(buildItem(sourceUrl = "https://example.com/script.user.js").isGithubHosted)
        assertFalse(buildItem(sourceUrl = "http://127.0.0.1:12345/my-script.user.js").isGithubHosted)
        assertFalse(buildItem(sourceUrl = "https://raw.githubusercontent.com/foo/bar/main/a.js").isGithubHosted)
    }

    @Test
    fun `isGithubHosted returns true for github releases URL`() {
        val url = "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js"
        assertTrue(buildItem(sourceUrl = url).isGithubHosted)
    }

    @Test
    fun `isGithubHosted returns true for minimal github owner-repo URL`() {
        assertTrue(buildItem(sourceUrl = "https://github.com/owner/repo").isGithubHosted)
    }

    private fun buildItem(sourceUrl: String?): ScriptUiItem = ScriptUiItem(
        identifier = ScriptIdentifier("test/Script"),
        name = "Script",
        version = "1.0.0",
        releaseTag = null,
        author = null,
        enabled = false,
        isPreset = false,
        conflictNames = emptyList(),
        sourceUrl = sourceUrl,
    )
}
