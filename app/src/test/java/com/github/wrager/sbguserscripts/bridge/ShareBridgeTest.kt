package com.github.wrager.sbguserscripts.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class ShareBridgeTest {

    private lateinit var context: Context
    private lateinit var bridge: ShareBridge

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        context = mockk()
        bridge = ShareBridge(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `open starts activity with correct URL`() {
        val mockUri = mockk<Uri>()
        every { Uri.parse("https://sbg-game.ru") } returns mockUri
        every { context.startActivity(any()) } returns Unit

        bridge.open("https://sbg-game.ru")

        verify { Uri.parse("https://sbg-game.ru") }
        verify { context.startActivity(any()) }
    }

    @Test
    fun `open calls startActivity`() {
        every { Uri.parse(any<String>()) } returns mockk()
        every { context.startActivity(any()) } returns Unit

        bridge.open("https://example.com")

        verify { context.startActivity(any()) }
    }
}
