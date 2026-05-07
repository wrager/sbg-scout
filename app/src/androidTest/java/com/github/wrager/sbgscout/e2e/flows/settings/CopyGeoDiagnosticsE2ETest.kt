package com.github.wrager.sbgscout.e2e.flows.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Клик "Скопировать диагностику геолокации" в settings overlay должен:
 * 1) собрать diagnostic-текст с секцией "Лог геолокации:";
 * 2) включить туда записи [GameActivity.geoDiagnosticLog], которые
 *    `GameActivity.logGeo` пишет при работе геолокации (запросе permission,
 *    callback от LocationManager и т.п.);
 * 3) положить итоговый текст в системный clipboard;
 * 4) НЕ открыть GitHub Issues (в отличие от существующего "Сообщить об ошибке"):
 *    flow рассчитан на нерезраба, который потом сам вставит текст в чат/email.
 *
 * Тест собирает фикстуру с `navigator.geolocation.getCurrentPosition`, чтобы
 * GameActivity успел зайти в `onGeolocationPermissionsShowPrompt` и наполнить
 * geoDiagnosticLog хотя бы одной строкой.
 *
 * Permission на FINE/COARSE выдаётся [E2ETestBase] через `GrantPermissionRule`.
 * `BuildConfig.GEOLOCATION_DIAGNOSTICS=true` для `instr` буилд типа явно
 * выставлен в `app/build.gradle.kts`, иначе preference был бы скрыт и
 * `performClick` пробрасывал бы on-click в скрытый элемент.
 */
class CopyGeoDiagnosticsE2ETest : E2ETestBase() {

    @Test
    fun copyGeoDiagnostics_putsClipboardWithGeoSection() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-geolocation.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        // Дождёмся, пока WebView получит результат геолокации (или ошибку):
        // только после этого geoDiagnosticLog успеет наполниться записями
        // онGeolocationPermissionsShowPrompt + checkSelfPermission.
        waitForGeoResult(game)

        val overlay = game.openSettings()
        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_COPY_GEO_DIAGNOSTICS)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboardText = readClipboardOnMainSync(targetContext)
        assertNotNull("Clipboard должен содержать диагностику", clipboardText)
        val text = clipboardText!!
        assertTrue("Должен быть header SBG Scout: $text", text.contains("SBG Scout"))
        assertTrue("Должна быть секция 'Лог геолокации:': $text", text.contains("Лог геолокации:"))
        assertTrue(
            "Должна быть запись onGeolocationPermissionsShowPrompt: $text",
            text.contains("onGeolocationPermissionsShowPrompt"),
        )
        assertTrue(
            "Должен быть результат checkSelfPermission FINE/COARSE: $text",
            text.contains("checkSelfPermission FINE="),
        )
    }

    private fun waitForGeoResult(game: GameScreen) {
        val deadline = System.currentTimeMillis() + GEO_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val raw = game.evaluateJs("window.__sbg_geo_result")
            if (raw != "null") return
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun readClipboardOnMainSync(context: Context): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = manager.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    private companion object {
        const val GEO_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
