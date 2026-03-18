package com.github.wrager.sbguserscripts.script.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetScriptsTest {

    @Test
    fun `ALL contains exactly four preset scripts`() {
        assertEquals(4, PresetScripts.ALL.size)
    }

    @Test
    fun `each preset has non-empty download and update URLs`() {
        for (preset in PresetScripts.ALL) {
            assertTrue(
                "${preset.displayName} has empty downloadUrl",
                preset.downloadUrl.isNotBlank(),
            )
            assertTrue(
                "${preset.displayName} has empty updateUrl",
                preset.updateUrl.isNotBlank(),
            )
        }
    }

    @Test
    fun `each preset has unique identifier`() {
        val identifiers = PresetScripts.ALL.map { it.identifier }
        assertEquals(identifiers.size, identifiers.toSet().size)
    }

    @Test
    fun `SVP identifier matches expected value`() {
        assertEquals(
            "github.com/wrager/sbg-vanilla-plus",
            PresetScripts.SVP.identifier.value,
        )
    }

    @Test
    fun `ANMILES has fallback download URL`() {
        assertNotNull(PresetScripts.ANMILES.fallbackDownloadUrl)
    }

    @Test
    fun `display names match script names`() {
        assertEquals("SBG Vanilla+", PresetScripts.SVP.displayName)
        assertEquals("SBG Enhanced UI", PresetScripts.EUI.displayName)
        assertEquals("SBG CUI", PresetScripts.CUI.displayName)
        assertEquals("SBG plus", PresetScripts.ANMILES.displayName)
    }
}
