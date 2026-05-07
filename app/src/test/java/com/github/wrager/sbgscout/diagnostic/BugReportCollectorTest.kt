package com.github.wrager.sbgscout.diagnostic

import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BugReportCollectorTest {

    private lateinit var consoleLogBuffer: ConsoleLogBuffer

    private val testDevice = BugReportCollector.DeviceInfo(
        model = "Google Pixel 7",
        androidVersion = "14",
        sdkVersion = 34,
        webViewVersion = "120.0.6099.144",
    )

    @Before
    fun setUp() {
        consoleLogBuffer = mockk()
    }

    @Test
    fun `clipboard text contains device info and version`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue(report.clipboardText.contains("SBG Scout v0.11.0"))
        assertTrue(report.clipboardText.contains("Android 14 (API 34)"))
        assertTrue(report.clipboardText.contains("Device: Google Pixel 7"))
        assertTrue(report.clipboardText.contains("WebView: 120.0.6099.144"))
    }

    @Test
    fun `clipboard text contains injected scripts with checkmark`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(
            userScript("SVP", "0.6.0"),
            userScript("EUI", "1.2.0"),
        )
        val snapshot = setOf("test/SVP::0.6.0", "test/EUI::1.2.0")

        val report = collector.collect(scripts, snapshot, testDevice)

        assertTrue(report.clipboardText.contains("Скрипты:"))
        assertTrue(report.clipboardText.contains("✅ SVP v0.6.0"))
        assertTrue(report.clipboardText.contains("✅ EUI v1.2.0"))
    }

    @Test
    fun `disabled script shows empty checkbox`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(userScript("CUI", "0.8.0", enabled = false))
        val snapshot = emptySet<String>()

        val report = collector.collect(scripts, snapshot, testDevice)

        assertTrue(report.clipboardText.contains("⬜ CUI v0.8.0"))
    }

    @Test
    fun `enabled but not injected script shows pending marker`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(userScript("SVP", "0.6.0"))
        // Снапшот пуст — скрипт включён, но не инжектирован
        val snapshot = emptySet<String>()

        val report = collector.collect(scripts, snapshot, testDevice)

        assertTrue(report.clipboardText.contains("⏳ SVP v0.6.0"))
    }

    @Test
    fun `all three script states shown correctly`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(
            userScript("SVP", "0.6.0"),
            userScript("EUI", "1.2.0"),
            userScript("CUI", "0.8.0", enabled = false),
        )
        // Только SVP в снапшоте — EUI включён, но не применён
        val snapshot = setOf("test/SVP::0.6.0")

        val report = collector.collect(scripts, snapshot, testDevice)

        assertTrue(report.clipboardText.contains("✅ SVP v0.6.0"))
        assertTrue(report.clipboardText.contains("⏳ EUI v1.2.0"))
        assertTrue(report.clipboardText.contains("⬜ CUI v0.8.0"))
    }

    @Test
    fun `null snapshot marks all enabled scripts as pending`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(
            userScript("SVP", "0.6.0"),
            userScript("CUI", "0.8.0", enabled = false),
        )

        val report = collector.collect(scripts, injectedSnapshot = null, testDevice)

        assertTrue(report.clipboardText.contains("⏳ SVP v0.6.0"))
        assertTrue(report.clipboardText.contains("⬜ CUI v0.8.0"))
    }

    @Test
    fun `clipboard text contains console log`() {
        every { consoleLogBuffer.format() } returns "[2025-01-01T00:00:00Z] [error] TypeError: null"
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue(report.clipboardText.contains("Лог консоли:"))
        assertTrue(report.clipboardText.contains("[error] TypeError: null"))
    }

    @Test
    fun `clipboard text omits empty sections`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue("Скрипты:" !in report.clipboardText)
        assertTrue("Лог консоли:" !in report.clipboardText)
        assertTrue("Лог геолокации:" !in report.clipboardText)
    }

    @Test
    fun `clipboard text contains geo log when provided`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val geoLog = GeoDiagnosticLog().apply {
            add("[geo] checkSelfPermission FINE=true COARSE=true")
            add("[geo] onGeolocationPermissionsShowPrompt origin=https://sbg-game.ru hasPermission=true")
        }

        val report = collector.collect(
            allScripts = emptyList(),
            injectedSnapshot = null,
            deviceInfo = testDevice,
            geoLog = geoLog,
        )

        assertTrue(report.clipboardText.contains("Лог геолокации:"))
        assertTrue(report.clipboardText.contains("checkSelfPermission FINE=true COARSE=true"))
        assertTrue(report.clipboardText.contains("onGeolocationPermissionsShowPrompt origin=https://sbg-game.ru"))
    }

    @Test
    fun `clipboard text omits geo log section when log is empty`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val emptyGeoLog = GeoDiagnosticLog()

        val report = collector.collect(
            allScripts = emptyList(),
            injectedSnapshot = null,
            deviceInfo = testDevice,
            geoLog = emptyGeoLog,
        )

        assertTrue("Лог геолокации:" !in report.clipboardText)
    }

    @Test
    fun `clipboard text omits webview line when version is null`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val deviceWithoutWebView = testDevice.copy(webViewVersion = null)

        val report = collector.collect(emptyList(), injectedSnapshot = null, deviceWithoutWebView)

        assertTrue("WebView:" !in report.clipboardText)
    }

    @Test
    fun `issue url contains template and version params`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue(report.issueUrl.startsWith("https://github.com/wrager/sbg-scout/issues/new?"))
        assertTrue(report.issueUrl.contains("template=bug_report.yml"))
        assertTrue(report.issueUrl.contains("apk-version=0.11.0"))
    }

    @Test
    fun `issue url contains android version with webview`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        // android-version=14 (API 34) (WebView 120.0.6099.144) — URL-encoded
        assertTrue(report.issueUrl.contains("android-version=14%20"))
        assertTrue(report.issueUrl.contains("WebView"))
    }

    @Test
    fun `issue url contains device model`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue(report.issueUrl.contains("device=Google%20Pixel%207"))
    }

    @Test
    fun `issue url contains scripts param when scripts present`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(userScript("SVP", "0.6.0"))
        val snapshot = setOf("test/SVP::0.6.0")

        val report = collector.collect(scripts, snapshot, testDevice)

        assertTrue(report.issueUrl.contains("scripts="))
    }

    @Test
    fun `issue url omits scripts param when no scripts`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue("scripts=" !in report.issueUrl)
    }

    @Test
    fun `works without console log buffer`() {
        val collector = BugReportCollector("0.11.0", consoleLogBuffer = null)

        val report = collector.collect(emptyList(), injectedSnapshot = null, testDevice)

        assertTrue(report.clipboardText.contains("SBG Scout v0.11.0"))
        assertTrue("Лог консоли:" !in report.clipboardText)
    }

    @Test
    fun `urlEncode encodes spaces as percent20`() {
        assertEquals("hello%20world", BugReportCollector.urlEncode("hello world"))
    }

    @Test
    fun `urlEncode preserves unreserved characters`() {
        assertEquals("abc-123_test.v~2", BugReportCollector.urlEncode("abc-123_test.v~2"))
    }

    @Test
    fun `urlEncode encodes cyrillic characters`() {
        val encoded = BugReportCollector.urlEncode("Тест")
        assertTrue(encoded.startsWith("%"))
        assertTrue(!encoded.contains("Т"))
    }

    @Test
    fun `script without version shows name only`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(userScript("Custom Script", null))
        val snapshot = setOf("test/Custom Script::")

        val report = collector.collect(scripts, snapshot, testDevice)

        assertTrue(report.clipboardText.contains("✅ Custom Script"))
        assertTrue("✅ Custom Script v" !in report.clipboardText)
    }

    private fun userScript(
        name: String,
        version: String?,
        enabled: Boolean = true,
    ): UserScript = UserScript(
        identifier = ScriptIdentifier("test/$name"),
        header = ScriptHeader(name = name, version = version),
        sourceUrl = null,
        updateUrl = null,
        content = "",
        enabled = enabled,
    )
}
