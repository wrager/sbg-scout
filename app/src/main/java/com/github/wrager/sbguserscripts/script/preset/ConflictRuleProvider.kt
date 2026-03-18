package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptConflict
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier

interface ConflictRuleProvider {
    fun conflictsFor(identifier: ScriptIdentifier): List<ScriptConflict>
}
