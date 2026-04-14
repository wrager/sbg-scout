package com.github.wrager.sbgscout.e2e.flows.smoke

import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет мост [com.github.wrager.sbgscout.bridge.ClipboardBridge]
 * (регистрируется как `Android` в JS).
 *
 * Вызов `Android.writeText("hello")` из JS должен помещать "hello" в системный
 * буфер обмена, а `Android.readText()` должен вернуть ровно то, что положили.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardBridgeE2ETest : E2ETestBase() {

    @Test
    fun writeText_placesTextOnSystemClipboard() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        game.evaluateJs("Android.writeText('hello from e2e')")

        val roundtrip = game.evaluateJs("Android.readText()")
        assertEquals("\"hello from e2e\"", roundtrip)

        val clipboardText = readSystemClipboardOnMainThread()
        assertEquals("hello from e2e", clipboardText)
    }

    private fun readSystemClipboardOnMainThread(): String? {
        var clipboardText: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardText = manager.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return clipboardText
    }
}
