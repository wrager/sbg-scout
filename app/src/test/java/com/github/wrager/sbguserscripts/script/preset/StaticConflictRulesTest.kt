package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StaticConflictRulesTest {

    private lateinit var rules: StaticConflictRules

    @Before
    fun setUp() {
        rules = StaticConflictRules()
    }

    @Test
    fun `SVP conflicts with EUI, CUI, and Anmiles`() {
        val conflicts = rules.conflictsFor(PresetScripts.SVP.identifier)

        assertEquals(3, conflicts.size)
        val conflictingIds = conflicts.map { it.conflictsWith }.toSet()
        assertTrue(conflictingIds.contains(PresetScripts.EUI.identifier))
        assertTrue(conflictingIds.contains(PresetScripts.CUI.identifier))
        assertTrue(conflictingIds.contains(PresetScripts.ANMILES.identifier))
    }

    @Test
    fun `EUI conflicts only with SVP`() {
        val conflicts = rules.conflictsFor(PresetScripts.EUI.identifier)

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.SVP.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `CUI conflicts only with SVP`() {
        val conflicts = rules.conflictsFor(PresetScripts.CUI.identifier)

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.SVP.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `Anmiles conflicts only with SVP`() {
        val conflicts = rules.conflictsFor(PresetScripts.ANMILES.identifier)

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.SVP.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `unknown script has no conflicts`() {
        val conflicts = rules.conflictsFor(ScriptIdentifier("unknown/script"))

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `returned conflicts always have queried identifier as scriptIdentifier`() {
        val euiConflicts = rules.conflictsFor(PresetScripts.EUI.identifier)

        for (conflict in euiConflicts) {
            assertEquals(PresetScripts.EUI.identifier, conflict.scriptIdentifier)
        }
    }

    @Test
    fun `conflicts are bidirectional`() {
        val svpConflictsWithEui = rules.conflictsFor(PresetScripts.SVP.identifier)
            .any { it.conflictsWith == PresetScripts.EUI.identifier }
        val euiConflictsWithSvp = rules.conflictsFor(PresetScripts.EUI.identifier)
            .any { it.conflictsWith == PresetScripts.SVP.identifier }

        assertTrue(svpConflictsWithEui)
        assertTrue(euiConflictsWithSvp)
    }
}
