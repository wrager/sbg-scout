package com.github.wrager.sbguserscripts.script.storage

import com.github.wrager.sbguserscripts.script.model.ScriptHeader
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import org.json.JSONArray
import org.json.JSONObject

internal object ScriptSerializer {

    fun toJson(script: UserScript): JSONObject {
        return JSONObject().apply {
            put("identifier", script.identifier.value)
            put("sourceUrl", script.sourceUrl)
            put("updateUrl", script.updateUrl)
            put("enabled", script.enabled)
            put("isPreset", script.isPreset)
            put("header", headerToJson(script.header))
        }
    }

    fun fromJson(json: JSONObject, content: String): UserScript {
        val headerJson = json.getJSONObject("header")
        return UserScript(
            identifier = ScriptIdentifier(json.getString("identifier")),
            header = headerFromJson(headerJson),
            sourceUrl = json.optString("sourceUrl", null),
            updateUrl = json.optString("updateUrl", null),
            content = content,
            enabled = json.optBoolean("enabled", false),
            isPreset = json.optBoolean("isPreset", false),
        )
    }

    private fun headerToJson(header: ScriptHeader): JSONObject {
        return JSONObject().apply {
            put("name", header.name)
            putOpt("version", header.version)
            putOpt("description", header.description)
            putOpt("author", header.author)
            putOpt("namespace", header.namespace)
            put("match", JSONArray(header.match))
            put("grant", JSONArray(header.grant))
            putOpt("downloadUrl", header.downloadUrl)
            putOpt("updateUrl", header.updateUrl)
            putOpt("runAt", header.runAt)
            putOpt("iconUrl", header.iconUrl)
        }
    }

    private fun headerFromJson(json: JSONObject): ScriptHeader {
        return ScriptHeader(
            name = json.getString("name"),
            version = json.optString("version", null),
            description = json.optString("description", null),
            author = json.optString("author", null),
            namespace = json.optString("namespace", null),
            match = json.optJSONArray("match").toStringList(),
            grant = json.optJSONArray("grant").toStringList(),
            downloadUrl = json.optString("downloadUrl", null),
            updateUrl = json.optString("updateUrl", null),
            runAt = json.optString("runAt", null),
            iconUrl = json.optString("iconUrl", null),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
