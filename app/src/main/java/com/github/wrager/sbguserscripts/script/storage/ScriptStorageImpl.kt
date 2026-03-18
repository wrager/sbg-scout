package com.github.wrager.sbguserscripts.script.storage

import android.content.SharedPreferences
import android.util.Log
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript
import org.json.JSONException
import org.json.JSONObject

class ScriptStorageImpl(
    private val sharedPreferences: SharedPreferences,
    private val fileStorage: ScriptFileStorage,
) : ScriptStorage {

    override fun getAll(): List<UserScript> {
        return sharedPreferences.all.mapNotNull { (key, value) ->
            try {
                val jsonString = value as? String ?: return@mapNotNull null
                val json = JSONObject(jsonString)
                val identifier = ScriptIdentifier(key)
                val content = fileStorage.readContent(identifier) ?: ""
                ScriptSerializer.fromJson(json, content)
            } catch (exception: JSONException) {
                Log.e(TAG, "Failed to deserialize script: $key", exception)
                null
            }
        }
    }

    override fun save(script: UserScript) {
        val json = ScriptSerializer.toJson(script)
        sharedPreferences.edit()
            .putString(script.identifier.value, json.toString())
            .apply()
        fileStorage.writeContent(script.identifier, script.content)
    }

    override fun delete(identifier: ScriptIdentifier) {
        sharedPreferences.edit()
            .remove(identifier.value)
            .apply()
        fileStorage.deleteContent(identifier)
    }

    override fun getEnabled(): List<UserScript> {
        return getAll().filter { it.enabled }
    }

    override fun setEnabled(identifier: ScriptIdentifier, enabled: Boolean) {
        val jsonString = sharedPreferences.getString(identifier.value, null) ?: return
        try {
            val json = JSONObject(jsonString)
            json.put("enabled", enabled)
            sharedPreferences.edit()
                .putString(identifier.value, json.toString())
                .apply()
        } catch (exception: JSONException) {
            Log.e(TAG, "Failed to update enabled state: ${identifier.value}", exception)
        }
    }

    companion object {
        private const val TAG = "ScriptStorage"
    }
}
