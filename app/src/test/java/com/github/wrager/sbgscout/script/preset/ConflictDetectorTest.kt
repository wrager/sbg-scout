package com.github.wrager.sbgscout.script.preset

import com.github.wrager.sbgscout.script.model.ScriptConflict
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.ScriptVersion
import com.github.wrager.sbgscout.script.model.VersionConstraint
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
            candidateVersion = null,
            enabledVersions = emptyMap(),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `detects conflict when conflicting script is enabled`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(PresetScripts.EUI.identifier to ScriptVersion("8.1.0")),
        )

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.EUI.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `no conflict when non-conflicting script is enabled`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.CUI.identifier,
            candidateVersion = null,
            enabledVersions = mapOf(PresetScripts.EUI.identifier to ScriptVersion("8.1.0")),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `SVP conflict with EUI is resolved when EUI version is at least 8_2_0`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(PresetScripts.EUI.identifier to ScriptVersion("8.2.0")),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `SVP conflict with EUI remains when EUI version is below 8_2_0`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(PresetScripts.EUI.identifier to ScriptVersion("8.1.99")),
        )

        assertEquals(1, conflicts.size)
    }

    @Test
    fun `SVP conflict with EUI is resolved when EUI version is higher than 8_2_0`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(PresetScripts.EUI.identifier to ScriptVersion("8.3.1")),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `SVP conflict with EUI remains when EUI version is unknown`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(PresetScripts.EUI.identifier to null),
        )

        assertEquals(1, conflicts.size)
    }

    @Test
    fun `EUI as candidate is compatible with SVP when EUI version is at least 8_2_0`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.EUI.identifier,
            candidateVersion = ScriptVersion("8.2.0"),
            enabledVersions = mapOf(PresetScripts.SVP.identifier to ScriptVersion("0.8.0")),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `EUI as candidate conflicts with SVP when EUI version is below 8_2_0`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.EUI.identifier,
            candidateVersion = ScriptVersion("8.1.0"),
            enabledVersions = mapOf(PresetScripts.SVP.identifier to ScriptVersion("0.8.0")),
        )

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.SVP.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `SVP conflict with CUI is unconditional`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(PresetScripts.CUI.identifier to ScriptVersion("999.0.0")),
        )

        assertEquals(1, conflicts.size)
        assertEquals(PresetScripts.CUI.identifier, conflicts[0].conflictsWith)
    }

    @Test
    fun `detects multiple conflicts`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = ScriptVersion("0.8.0"),
            enabledVersions = mapOf(
                PresetScripts.EUI.identifier to ScriptVersion("8.1.0"),
                PresetScripts.CUI.identifier to ScriptVersion("1.0.0"),
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
            candidateVersion = null,
            enabledVersions = mapOf(scriptB to null),
        )

        assertEquals(1, conflicts.size)
        assertEquals("Custom conflict", conflicts[0].reason)
    }

    @Test
    fun `candidate version satisfies constraint on candidate identifier`() {
        val customProvider = mockk<ConflictRuleProvider>()
        val scriptA = ScriptIdentifier("custom/a")
        val scriptB = ScriptIdentifier("custom/b")
        val customConflict = ScriptConflict(
            scriptIdentifier = scriptA,
            conflictsWith = scriptB,
            reason = "Custom conflict",
            compatibleSince = VersionConstraint(scriptA, ScriptVersion("2.0.0")),
        )

        every { customProvider.conflictsFor(scriptA) } returns listOf(customConflict)

        val customDetector = ConflictDetector(customProvider)

        val resolved = customDetector.detectConflicts(
            candidateIdentifier = scriptA,
            candidateVersion = ScriptVersion("2.0.0"),
            enabledVersions = mapOf(scriptB to null),
        )
        assertTrue(resolved.isEmpty())

        val remaining = customDetector.detectConflicts(
            candidateIdentifier = scriptA,
            candidateVersion = ScriptVersion("1.9.9"),
            enabledVersions = mapOf(scriptB to null),
        )
        assertEquals(1, remaining.size)
    }

    @Test
    fun `no conflict when conflicting script is not in enabled set`() {
        val conflicts = detector.detectConflicts(
            candidateIdentifier = PresetScripts.SVP.identifier,
            candidateVersion = null,
            enabledVersions = mapOf(ScriptIdentifier("other/script") to null),
        )

        assertTrue(conflicts.isEmpty())
    }
}
