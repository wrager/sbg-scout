package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptConflict
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConflictDetectorTest {

    private lateinit var detector: ConflictDetector

    @Before
    fun setUp() {
        detector = ConflictDetector(StaticConflictRules())
    }

    @Test
    fun `no conflicts when no scripts enabled`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            enabledIdentifiers = emptySet(),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `detects conflict when conflicting script is enabled`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            enabledIdentifiers = setOf(PresetScripts.EUI.identifier),
        )

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.EUI.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `no conflict when non-conflicting script is enabled`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.CUI.identifier,
            enabledIdentifiers = setOf(PresetScripts.EUI.identifier),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `detects multiple conflicts`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            enabledIdentifiers = setOf(
                PresetScripts.EUI.identifier,
                PresetScripts.CUI.identifier,
            ),
        )

        assertEquals(2, conflicts.size)
    }

    @Test
    fun `works with custom ConflictRuleProvider`() {
        val customProvider = mockk<ConflictRuleProvider>()
        val scriptA = ScriptIdentifier("custom/a")
        val scriptB = ScriptIdentifier("custom/b")
        val customConflict = ScriptConflict(scriptA, scriptB, "Custom conflict")

        every { customProvider.conflictsFor(scriptA) } returns listOf(customConflict)

        val customDetector = ConflictDetector(customProvider)
        val conflicts = customDetector.detectConflicts(
            candidateIdentifier = scriptA,
            enabledIdentifiers = setOf(scriptB),
        )

        assertEquals(1, conflicts.size)
        assertEquals("Custom conflict", conflicts[0].reason)
    }

    @Test
    fun `no conflict when conflicting script is not in enabled set`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            enabledIdentifiers = setOf(ScriptIdentifier("other/script")),
        )

        assertTrue(conflicts.isEmpty())
    }
}
