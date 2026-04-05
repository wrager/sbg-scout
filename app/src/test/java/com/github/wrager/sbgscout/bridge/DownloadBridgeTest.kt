package com.github.wrager.sbgscout.bridge

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DownloadBridgeTest {

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun `saveBlob decodes base64 and calls save with filename mime and bytes`() {
        val expectedBytes = byteArrayOf(1, 2, 3, 4)
        every { Base64.decode("AQIDBA==", Base64.DEFAULT) } returns expectedBytes
        var captured: Triple<String, String, ByteArray>? = null
        val bridge = DownloadBridge(
            save = { filename, mimeType, bytes ->
                captured = Triple(filename, mimeType, bytes)
                filename
            },
            onResult = {},
        )

        bridge.saveBlob("AQIDBA==", "svp-favorites-2026-04-05.json", "application/json")

        assertEquals("svp-favorites-2026-04-05.json", captured?.first)
        assertEquals("application/json", captured?.second)
        assertArrayEquals(expectedBytes, captured?.third)
    }

    @Test
    fun `saveBlob reports saved name from save callback`() {
        every { Base64.decode(any<String>(), any()) } returns byteArrayOf(0)
        var reported: String? = "initial"
        val bridge = DownloadBridge(
            save = { _, _, _ -> "Downloads/file.json" },
            onResult = { reported = it },
        )

        bridge.saveBlob("AA==", "file.json", "application/json")

        assertEquals("Downloads/file.json", reported)
    }

    @Test
    fun `saveBlob reports null when save returns null`() {
        every { Base64.decode(any<String>(), any()) } returns byteArrayOf(0)
        var reported: String? = "initial"
        val bridge = DownloadBridge(
            save = { _, _, _ -> null },
            onResult = { reported = it },
        )

        bridge.saveBlob("AA==", "file.json", "application/json")

        assertNull(reported)
    }

    @Test
    fun `saveBlob reports null and skips save on invalid base64`() {
        every { Base64.decode("!!!", Base64.DEFAULT) } throws IllegalArgumentException("bad base64")
        var saveCalled = false
        var reported: String? = "initial"
        val bridge = DownloadBridge(
            save = { _, _, _ ->
                saveCalled = true
                "ok"
            },
            onResult = { reported = it },
        )

        bridge.saveBlob("!!!", "file.json", "application/json")

        assertEquals(false, saveCalled)
        assertNull(reported)
    }
}
