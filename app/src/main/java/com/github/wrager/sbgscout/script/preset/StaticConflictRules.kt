package com.github.wrager.sbgscout.script.preset

import com.github.wrager.sbgscout.script.model.ScriptConflict
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.VersionConstraint

class StaticConflictRules : ConflictRuleProvider {

    private val rules: List<ScriptConflict> = listOf(
        ScriptConflict(
            scriptIdentifier = PresetScripts.SVP.identifier,
            conflictsWith = PresetScripts.EUI.identifier,
            reason = "SVP is incompatible with EUI versions below 8.2.0",
            compatibleSince = VersionConstraint(
                identifier = PresetScripts.EUI.identifier,
                minVersion = ScriptVersion("8.2.0"),
            ),
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
                        compatibleSince = conflict.compatibleSince,
                    )
                }
            }
    }
}
