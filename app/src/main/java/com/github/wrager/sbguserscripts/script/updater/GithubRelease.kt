package com.github.wrager.sbguserscripts.script.updater

data class GithubRelease(
    val tagName: String,
    val assets: List<GithubAsset>,
)

data class GithubAsset(
    val name: String,
    val downloadUrl: String,
)
