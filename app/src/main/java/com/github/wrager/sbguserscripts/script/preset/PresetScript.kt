package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier

data class PresetScript(
    val identifier: ScriptIdentifier,
    val displayName: String,
    val downloadUrl: String,
    val updateUrl: String,
    val fallbackDownloadUrl: String? = null,
)
