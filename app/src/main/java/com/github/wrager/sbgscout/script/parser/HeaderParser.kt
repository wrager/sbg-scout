package com.github.wrager.sbgscout.script.parser

import com.github.wrager.sbgscout.script.model.ScriptHeader

object HeaderParser {
    private val HEADER_START = Regex("""^\s*//\s*==UserScript==""")
    private val HEADER_END = Regex("""^\s*//\s*==/UserScript==""")
    private val ENTRY_PATTERN = Regex("""^\s*//\s*@(\S+)\s+(.+)$""")

    private val KEY_ALIASES =
        mapOf(
            "downloadURL" to "downloadUrl",
            "updateURL" to "updateUrl",
            "iconURL" to "iconUrl",
            "icon64URL" to "iconUrl",
            "homepageURL" to "homepageUrl",
            "supportURL" to "supportUrl",
            "run-at" to "runAt",
        )

    fun parse(scriptContent: String): ScriptHeader? {
        val rawEntries = extractRawEntries(scriptContent)

        if (rawEntries.isEmpty()) return null

        val name = rawEntries.firstValue("name") ?: return null

        return ScriptHeader(
            name = name,
            version = rawEntries.firstValue("version"),
            description = rawEntries.firstValue("description"),
            author = rawEntries.firstValue("author"),
            namespace = rawEntries.firstValue("namespace"),
            match = rawEntries.allValues("match"),
            grant = rawEntries.allValues("grant"),
            downloadUrl = rawEntries.firstValue("downloadURL"),
            updateUrl = rawEntries.firstValue("updateURL"),
            runAt = rawEntries.firstValue("run-at"),
            iconUrl = rawEntries.firstValue("iconURL") ?: rawEntries.firstValue("icon64URL"),
            rawEntries = normalizeEntries(rawEntries),
        )
    }

    private fun extractRawEntries(scriptContent: String): Map<String, MutableList<String>> {
        val rawEntries = mutableMapOf<String, MutableList<String>>()
        val headerLines =
            scriptContent
                .lineSequence()
                .dropWhile { !HEADER_START.containsMatchIn(it) }
                .drop(1)
                .takeWhile { !HEADER_END.containsMatchIn(it) }

        for (line in headerLines) {
            val matchResult = ENTRY_PATTERN.matchEntire(line) ?: continue
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues[2].trim()
            rawEntries.getOrPut(key) { mutableListOf() }.add(value)
        }

        return rawEntries
    }

    private fun normalizeEntries(rawEntries: Map<String, List<String>>): Map<String, List<String>> =
        rawEntries
            .map { (key, values) ->
                val normalizedKey = KEY_ALIASES.getOrDefault(key, key)
                normalizedKey to values
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, lists) -> lists.flatten() }

    private fun Map<String, List<String>>.firstValue(key: String): String? = get(key)?.firstOrNull()

    private fun Map<String, List<String>>.allValues(key: String): List<String> = get(key) ?: emptyList()
}
