package com.github.wrager.sbgscout.script.storage

import com.github.wrager.sbgscout.script.model.ScriptIdentifier

interface ScriptFileStorage {
    fun readContent(identifier: ScriptIdentifier): String?

    fun writeContent(
        identifier: ScriptIdentifier,
        content: String,
    )

    fun deleteContent(identifier: ScriptIdentifier)
}
