package com.github.wrager.sbgscout.script.preset

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `SVP conflicts with EUI and CUI`() {
        val conflicts = rules.conflictsFor(PresetScripts.SVP.identifier)

        assertEquals(2, conflicts.size)
        val conflictingIds = conflicts.map { it.conflictsWith }.toSet()
        assertTrue(conflictingIds.contains(PresetScripts.EUI.identifier))
        assertTrue(conflictingIds.contains(PresetScripts.CUI.identifier))
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
    fun `SVP-EUI conflict carries version constraint requiring EUI at least 8_2_0`() {
        val euiConflict = rules.conflictsFor(PresetScripts.SVP.identifier)
            .first { it.conflictsWith == PresetScripts.EUI.identifier }

        val constraint = euiConflict.compatibleSince
        assertEquals(PresetScripts.EUI.identifier, constraint?.identifier)
        assertEquals(ScriptVersion("8.2.0"), constraint?.minVersion)
    }

    @Test
    fun `SVP-CUI conflict has no version constraint`() {
        val cuiConflict = rules.conflictsFor(PresetScripts.SVP.identifier)
            .first { it.conflictsWith == PresetScripts.CUI.identifier }

        assertNull(cuiConflict.compatibleSince)
    }

    @Test
    fun `inverted EUI conflict preserves EUI version constraint`() {
        val inverted = rules.conflictsFor(PresetScripts.EUI.identifier)
            .first { it.conflictsWith == PresetScripts.SVP.identifier }

        val constraint = inverted.compatibleSince
        assertEquals(PresetScripts.EUI.identifier, constraint?.identifier)
        assertEquals(ScriptVersion("8.2.0"), constraint?.minVersion)
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
