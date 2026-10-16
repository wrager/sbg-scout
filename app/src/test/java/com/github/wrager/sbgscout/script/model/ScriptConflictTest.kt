package com.github.wrager.sbgscout.script.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScriptConflictTest {
    @Test
    fun `conflict holds correct identifiers and reason`() {
        val conflict =
            ScriptConflict(
                scriptIdentifier = ScriptIdentifier("script/a"),
                conflictsWith = ScriptIdentifier("script/b"),
                reason = "Both modify UI",
            )

        assertEquals(ScriptIdentifier("script/a"), conflict.scriptIdentifier)
        assertEquals(ScriptIdentifier("script/b"), conflict.conflictsWith)
        assertEquals("Both modify UI", conflict.reason)
    }

    @Test
    fun `two conflicts with same data are equal`() {
        val conflict1 =
            ScriptConflict(
                scriptIdentifier = ScriptIdentifier("script/a"),
                conflictsWith = ScriptIdentifier("script/b"),
                reason = "Conflict reason",
            )
        val conflict2 =
            ScriptConflict(
                scriptIdentifier = ScriptIdentifier("script/a"),
                conflictsWith = ScriptIdentifier("script/b"),
                reason = "Conflict reason",
            )

        assertEquals(conflict1, conflict2)
    }

    @Test
    fun `conflicts with different reasons are not equal`() {
        val conflict1 =
            ScriptConflict(
                scriptIdentifier = ScriptIdentifier("script/a"),
                conflictsWith = ScriptIdentifier("script/b"),
                reason = "Reason 1",
            )
        val conflict2 =
            ScriptConflict(
                scriptIdentifier = ScriptIdentifier("script/a"),
                conflictsWith = ScriptIdentifier("script/b"),
                reason = "Reason 2",
            )

        assertNotEquals(conflict1, conflict2)
    }
}
