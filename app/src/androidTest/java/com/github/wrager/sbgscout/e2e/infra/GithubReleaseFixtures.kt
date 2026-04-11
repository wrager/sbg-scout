package com.github.wrager.sbgscout.e2e.infra

import org.json.JSONArray
import org.json.JSONObject

/**
 * Генератор JSON-ответов в формате GitHub REST API, понимаемом
 * [com.github.wrager.sbgscout.script.updater.GithubReleaseProvider].
 *
 * Используется в e2e-тестах через [FakeGameServer.stubGithubReleasesList] /
 * [FakeGameServer.stubGithubReleaseLatest]. Минимальный набор полей: tag_name,
 * body, assets[name, browser_download_url]. Поле `prerelease` задаётся явно
 * только для тестов, проверяющих, что prerelease игнорируется.
 */
object GithubReleaseFixtures {

    data class ReleaseEntry(
        val tagName: String,
        val body: String = "",
        val assets: List<AssetEntry> = emptyList(),
        val prerelease: Boolean = false,
    )

    data class AssetEntry(
        val name: String,
        val downloadUrl: String,
    )

    /** Возвращает JSON для `/gh-api/repos/<owner>/<repo>/releases`. */
    fun releasesListJson(entries: List<ReleaseEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(entry.toJson())
        }
        return array.toString()
    }

    /**
     * Shortcut для AppUpdateChecker: один релиз с .apk-ассетом.
     * tag_name = "v<version>" — AppUpdateChecker убирает префикс "v" перед сравнением.
     */
    fun appUpdateReleasesJson(
        version: String,
        body: String = "New features",
        assetName: String = "sbg-scout.apk",
        owner: String = "wrager",
        repo: String = "sbg-scout",
    ): String = releasesListJson(
        listOf(
            ReleaseEntry(
                tagName = "v$version",
                body = body,
                assets = listOf(
                    AssetEntry(
                        name = assetName,
                        downloadUrl = "https://github.com/$owner/$repo/releases/download/v$version/$assetName",
                    ),
                ),
            ),
        ),
    )

    /**
     * Shortcut для Script version selection: список релизов скрипта с
     * `<asset>.user.js` в каждом (dispatcher использует этот URL для загрузки).
     */
    fun scriptReleasesJson(
        owner: String,
        repo: String,
        versions: List<String>,
        assetName: String,
    ): String = releasesListJson(
        versions.map { version ->
            ReleaseEntry(
                tagName = "v$version",
                body = "Release $version",
                assets = listOf(
                    AssetEntry(
                        name = assetName,
                        downloadUrl = "https://github.com/$owner/$repo/releases/download/v$version/$assetName",
                    ),
                ),
            )
        },
    )

    private fun ReleaseEntry.toJson(): JSONObject = JSONObject().apply {
        put("tag_name", tagName)
        put("body", body)
        put("prerelease", prerelease)
        val assetsArray = JSONArray()
        assets.forEach { asset ->
            val obj = JSONObject().apply {
                put("name", asset.name)
                put("browser_download_url", asset.downloadUrl)
            }
            assetsArray.put(obj)
        }
        put("assets", assetsArray)
    }
}
