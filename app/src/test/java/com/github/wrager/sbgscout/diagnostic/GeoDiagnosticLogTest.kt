package com.github.wrager.sbgscout.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDiagnosticLogTest {

    @Test
    fun `empty log formats to empty string`() {
        assertEquals("", GeoDiagnosticLog().format())
    }

    @Test
    fun `add stores entry and format includes message`() {
        val log = GeoDiagnosticLog()
        log.add("checkSelfPermission FINE=true")

        val text = log.format()
        assertTrue(text.contains("checkSelfPermission FINE=true"))
    }

    @Test
    fun `format prefixes each entry with iso timestamp`() {
        val log = GeoDiagnosticLog()
        log.add("first")

        val line = log.format()
        // ISO_INSTANT example: 2026-05-07T14:30:00.123Z
        val pattern = Regex("^\\[\\d{4}-\\d{2}-\\d{2}T.+Z\\] first$")
        assertTrue("Ожидаем ISO timestamp в начале строки: $line", line.matches(pattern))
    }

    @Test
    fun `ring buffer drops oldest entries above MAX_ENTRIES`() {
        val log = GeoDiagnosticLog()
        val total = GeoDiagnosticLog.MAX_ENTRIES + 5
        for (index in 0 until total) {
            log.add("entry-$index")
        }

        val snapshot = log.snapshot()
        assertEquals(GeoDiagnosticLog.MAX_ENTRIES, snapshot.size)
        // Первые 5 записей должны быть выброшены
        assertFalse(snapshot.any { it.message == "entry-0" })
        assertFalse(snapshot.any { it.message == "entry-4" })
        assertTrue(snapshot.any { it.message == "entry-5" })
        assertTrue(snapshot.any { it.message == "entry-${total - 1}" })
    }

    @Test
    fun `clear removes all entries`() {
        val log = GeoDiagnosticLog()
        log.add("one")
        log.add("two")

        log.clear()

        assertEquals("", log.format())
        assertTrue(log.snapshot().isEmpty())
    }
}
