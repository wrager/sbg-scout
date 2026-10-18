package com.github.wrager.sbgscout.script.storage

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript

interface ScriptStorage {
    fun getAll(): List<UserScript>

    fun contains(identifier: ScriptIdentifier): Boolean

    fun save(script: UserScript)

    fun delete(identifier: ScriptIdentifier)

    fun getEnabled(): List<UserScript>

    fun setEnabled(
        identifier: ScriptIdentifier,
        enabled: Boolean,
    )
}
