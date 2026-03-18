package com.github.wrager.sbguserscripts.script.storage

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier

interface ScriptFileStorage {
    fun readContent(identifier: ScriptIdentifier): String?
    fun writeContent(identifier: ScriptIdentifier, content: String)
    fun deleteContent(identifier: ScriptIdentifier)
}
