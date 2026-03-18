package com.github.wrager.sbguserscripts.script.updater

import org.json.JSONArray
import org.json.JSONObject

class GithubReleaseProvider(private val httpFetcher: HttpFetcher) {

    suspend fun fetchReleases(owner: String, repository: String): List<GithubRelease> {
        val url = "$API_BASE_URL/repos/$owner/$repository/releases"
        val response = httpFetcher.fetch(url, GITHUB_API_HEADERS)
        return parseReleases(response)
    }

    companion object {
        private const val API_BASE_URL = "https://api.github.com"
        private val GITHUB_API_HEADERS = mapOf("Accept" to "application/vnd.github+json")
        private val GITHUB_REPO_PATTERN = Regex("""github\.com/([^/]+)/([^/]+)""")

        fun extractOwnerAndRepository(url: String): Pair<String, String>? {
            val match = GITHUB_REPO_PATTERN.find(url) ?: return null
            return match.groupValues[1] to match.groupValues[2]
        }

        internal fun parseReleases(json: String): List<GithubRelease> {
            val array = JSONArray(json)
            return (0 until array.length())
                .map { array.getJSONObject(it) }
                .filter { !it.optBoolean("prerelease", false) }
                .map { it.toGithubRelease() }
        }

        private fun JSONObject.toGithubRelease(): GithubRelease {
            val assetsArray = getJSONArray("assets")
            val assets = (0 until assetsArray.length()).map { index ->
                assetsArray.getJSONObject(index).toGithubAsset()
            }
            return GithubRelease(
                tagName = getString("tag_name"),
                assets = assets,
            )
        }

        private fun JSONObject.toGithubAsset(): GithubAsset = GithubAsset(
            name = getString("name"),
            downloadUrl = getString("browser_download_url"),
        )
    }
}
