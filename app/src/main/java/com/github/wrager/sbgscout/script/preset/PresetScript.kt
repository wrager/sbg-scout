package com.github.wrager.sbgscout.script.preset

import com.github.wrager.sbgscout.script.model.ScriptIdentifier

data class PresetScript(
    val identifier: ScriptIdentifier,
    val displayName: String,
    val downloadUrl: String,
    val updateUrl: String,
    val fallbackDownloadUrl: String? = null,
    val enabledByDefault: Boolean = false,
    val description: String? = null,
)
