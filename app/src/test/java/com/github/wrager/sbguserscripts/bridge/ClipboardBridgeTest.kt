package com.github.wrager.sbguserscripts.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClipboardBridgeTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var bridge: ClipboardBridge

    @Before
    fun setUp() {
        context = mockk()
        clipboardManager = mockk()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        bridge = ClipboardBridge(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(ClipData::class)
    }

    @Test
    fun `readText returns text from clipboard`() {
        val clipItem = mockk<ClipData.Item>()
        val clipData = mockk<ClipData>()
        every { clipItem.text } returns "hello"
        every { clipData.getItemAt(0) } returns clipItem
        every { clipboardManager.primaryClip } returns clipData

        assertEquals("hello", bridge.readText())
    }

    @Test
    fun `readText returns empty string when clipboard is empty`() {
        every { clipboardManager.primaryClip } returns null

        assertEquals("", bridge.readText())
    }

    @Test
    fun `writeText puts text into clipboard`() {
        mockkStatic(ClipData::class)
        val mockClipData = mockk<ClipData>()
        every { ClipData.newPlainText(any(), eq("world")) } returns mockClipData
        every { clipboardManager.setPrimaryClip(mockClipData) } returns Unit

        bridge.writeText("world")

        verify { ClipData.newPlainText(any(), eq("world")) }
        verify { clipboardManager.setPrimaryClip(mockClipData) }
    }
}
