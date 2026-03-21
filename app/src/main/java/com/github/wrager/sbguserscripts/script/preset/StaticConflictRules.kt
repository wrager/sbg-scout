package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptConflict
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier

class StaticConflictRules : ConflictRuleProvider {

    private val rules: List<ScriptConflict> = listOf(
        ScriptConflict(
            scriptIdentifier = PresetScripts.SVP.identifier,
            conflictsWith = PresetScripts.EUI.identifier,
            reason = "SVP and EUI both modify the game UI and are incompatible",
        ),
        ScriptConflict(
            scriptIdentifier = PresetScripts.SVP.identifier,
            conflictsWith = PresetScripts.CUI.identifier,
            reason = "SVP and CUI both modify the game UI and are incompatible",
        ),
    )

    override fun conflictsFor(identifier: ScriptIdentifier): List<ScriptConflict> {
        return rules
            .filter { it.scriptIdentifier == identifier || it.conflictsWith == identifier }
            .map { conflict ->
                if (conflict.scriptIdentifier == identifier) {
                    conflict
                } else {
                    ScriptConflict(
                        scriptIdentifier = identifier,
                        conflictsWith = conflict.scriptIdentifier,
                        reason = conflict.reason,
                    )
                }
            }
    }
}
