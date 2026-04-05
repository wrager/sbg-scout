package com.github.wrager.sbgscout.script.preset

import com.github.wrager.sbgscout.script.model.ScriptConflict
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion

class ConflictDetector(private val ruleProvider: ConflictRuleProvider) {

    fun detectConflicts(
        candidateIdentifier: ScriptIdentifier,
        candidateVersion: ScriptVersion?,
        enabledVersions: Map<ScriptIdentifier, ScriptVersion?>,
    ): List<ScriptConflict> {
        return ruleProvider.conflictsFor(candidateIdentifier)
            .filter { it.conflictsWith in enabledVersions.keys }
            .filter { conflict ->
                !isResolvedByVersion(conflict, candidateIdentifier, candidateVersion, enabledVersions)
            }
    }

    private fun isResolvedByVersion(
        conflict: ScriptConflict,
        candidateIdentifier: ScriptIdentifier,
        candidateVersion: ScriptVersion?,
        enabledVersions: Map<ScriptIdentifier, ScriptVersion?>,
    ): Boolean {
        val constraint = conflict.compatibleSince ?: return false
        val actualVersion = if (constraint.identifier == candidateIdentifier) {
            candidateVersion
        } else {
            enabledVersions[constraint.identifier]
        } ?: return false
        return actualVersion >= constraint.minVersion
    }
}
