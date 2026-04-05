package com.github.wrager.sbgscout.script.model

data class ScriptConflict(
    val scriptIdentifier: ScriptIdentifier,
    val conflictsWith: ScriptIdentifier,
    val reason: String,
    val compatibleSince: VersionConstraint? = null,
)
