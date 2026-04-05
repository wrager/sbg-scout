package com.github.wrager.sbgscout.script.model

data class VersionConstraint(
    val identifier: ScriptIdentifier,
    val minVersion: ScriptVersion,
)
