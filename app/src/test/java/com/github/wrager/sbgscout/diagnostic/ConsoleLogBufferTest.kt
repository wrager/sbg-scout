package com.github.wrager.sbgscout.diagnostic

import android.webkit.ConsoleMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConsoleLogBufferTest {

    private lateinit var buffer: ConsoleLogBuffer

    @Before
    fun setUp() {
        buffer = ConsoleLogBuffer()
    }

    @Test
    fun `format returns empty string when buffer is empty`() {
        assertEquals("", buffer.format())
    }

    @Test
    fun `snapshot returns empty list when buffer is empty`() {
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `adds error messages`() {
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.ERROR, "something failed", "script.js", 42))

        val entries = buffer.snapshot()
        assertEquals(1, entries.size)
        assertEquals(ConsoleLogBuffer.Level.ERROR, entries[0].level)
        assertEquals("something failed", entries[0].message)
        assertEquals("script.js", entries[0].source)
        assertEquals(42, entries[0].lineNumber)
    }

    @Test
    fun `adds warning messages`() {
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.WARNING, "deprecation notice", "lib.js", 10))

        val entries = buffer.snapshot()
        assertEquals(1, entries.size)
        assertEquals(ConsoleLogBuffer.Level.WARNING, entries[0].level)
        assertEquals("deprecation notice", entries[0].message)
    }

    @Test
    fun `ignores non-error and non-warning messages`() {
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.LOG, "info message", "app.js", 1))
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.DEBUG, "debug message", "app.js", 2))
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.TIP, "tip message", "app.js", 3))

        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `evicts oldest entries when exceeding max`() {
        repeat(ConsoleLogBuffer.MAX_ENTRIES + 10) { index ->
            buffer.add(consoleMessage(ConsoleMessage.MessageLevel.ERROR, "error $index", "s.js", index))
        }

        val entries = buffer.snapshot()
        assertEquals(ConsoleLogBuffer.MAX_ENTRIES, entries.size)
        // Первая запись — 10-я (первые 10 вытеснены)
        assertEquals("error 10", entries.first().message)
        assertEquals("error ${ConsoleLogBuffer.MAX_ENTRIES + 9}", entries.last().message)
    }

    @Test
    fun `format produces expected output`() {
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.ERROR, "TypeError: null", "main.js", 100))
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.WARNING, "deprecated API", "vendor.js", 55))

        val formatted = buffer.format()
        val lines = formatted.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("[error]"))
        assertTrue(lines[0].contains("TypeError: null"))
        assertTrue(lines[0].contains("[main.js:100]"))
        assertTrue(lines[1].contains("[warn]"))
        assertTrue(lines[1].contains("deprecated API"))
    }

    @Test
    fun `clear removes all entries`() {
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.ERROR, "err", "s.js", 1))
        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
        assertEquals("", buffer.format())
    }

    @Test
    fun `format omits location section when source is null`() {
        // Покрывает ветку `entry.source?.let {..}.orEmpty()` = "" — когда sourceId
        // у ConsoleMessage null, в итоговой строке после текста сообщения не
        // должно быть хвоста с [source:line].
        buffer.add(consoleMessage(ConsoleMessage.MessageLevel.ERROR, "no source", source = null, line = 0))

        val line = buffer.format()
        assertTrue(line.contains("[error]"))
        assertTrue(line.contains("no source"))
        val afterMessage = line.substringAfter("no source")
        assertEquals("", afterMessage)
    }

    private fun consoleMessage(
        level: ConsoleMessage.MessageLevel,
        message: String,
        source: String?,
        line: Int,
    ): ConsoleMessage {
        val mock = mockk<ConsoleMessage>()
        every { mock.messageLevel() } returns level
        every { mock.message() } returns message
        every { mock.sourceId() } returns source
        every { mock.lineNumber() } returns line
        return mock
    }
}
