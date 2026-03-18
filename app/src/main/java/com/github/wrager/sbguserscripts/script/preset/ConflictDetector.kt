package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptConflict
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier

class ConflictDetector(private val ruleProvider: ConflictRuleProvider) {

    fun detectConflicts(
        candidateIdentifier: ScriptIdentifier,
        enabledIdentifiers: Set<ScriptIdentifier>,
    ): List<ScriptConflict> {
        return ruleProvider.conflictsFor(candidateIdentifier)
            .filter { it.conflictsWith in enabledIdentifiers }
    }
}
