package com.github.wrager.sbguserscripts.script.storage

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.model.UserScript

interface ScriptStorage {
    fun getAll(): List<UserScript>
    fun save(script: UserScript)
    fun delete(identifier: ScriptIdentifier)
    fun getEnabled(): List<UserScript>
    fun setEnabled(identifier: ScriptIdentifier, enabled: Boolean)
}
