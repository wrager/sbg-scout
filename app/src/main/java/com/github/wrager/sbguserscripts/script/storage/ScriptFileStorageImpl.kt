package com.github.wrager.sbguserscripts.script.storage

import android.util.Log
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import java.io.File
import java.io.IOException

class ScriptFileStorageImpl(private val scriptsDirectory: File) : ScriptFileStorage {

    init {
        if (!scriptsDirectory.exists()) {
            scriptsDirectory.mkdirs()
        }
    }

    override fun readContent(identifier: ScriptIdentifier): String? {
        val file = fileFor(identifier)
        return try {
            if (file.exists()) file.readText() else null
        } catch (exception: IOException) {
            Log.e(TAG, "Failed to read script content: ${identifier.value}", exception)
            null
        }
    }

    override fun writeContent(identifier: ScriptIdentifier, content: String) {
        val file = fileFor(identifier)
        try {
            file.writeText(content)
        } catch (exception: IOException) {
            Log.e(TAG, "Failed to write script content: ${identifier.value}", exception)
        }
    }

    override fun deleteContent(identifier: ScriptIdentifier) {
        val file = fileFor(identifier)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun fileFor(identifier: ScriptIdentifier): File {
        val sanitizedName = identifier.value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(scriptsDirectory, "$sanitizedName.user.js")
    }

    companion object {
        private const val TAG = "ScriptFileStorage"
    }
}
