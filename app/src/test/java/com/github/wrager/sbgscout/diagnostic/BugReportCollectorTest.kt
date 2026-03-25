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

        val report = collector.collect(emptyList(), testDevice)

        assertTrue(report.clipboardText.contains("SBG Scout v0.11.0"))
        assertTrue(report.clipboardText.contains("Android 14 (API 34)"))
        assertTrue(report.clipboardText.contains("Device: Google Pixel 7"))
        assertTrue(report.clipboardText.contains("WebView: 120.0.6099.144"))
    }

    @Test
    fun `clipboard text contains enabled scripts`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(
            userScript("SVP", "0.6.0"),
            userScript("EUI", "1.2.0"),
        )

        val report = collector.collect(scripts, testDevice)

        assertTrue(report.clipboardText.contains("Скрипты:"))
        assertTrue(report.clipboardText.contains("- SVP v0.6.0"))
        assertTrue(report.clipboardText.contains("- EUI v1.2.0"))
    }

    @Test
    fun `clipboard text contains console log`() {
        every { consoleLogBuffer.format() } returns "[2025-01-01T00:00:00Z] [error] TypeError: null"
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), testDevice)

        assertTrue(report.clipboardText.contains("Лог консоли:"))
        assertTrue(report.clipboardText.contains("[error] TypeError: null"))
    }

    @Test
    fun `clipboard text omits empty sections`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), testDevice)

        assertTrue("Скрипты:" !in report.clipboardText)
        assertTrue("Лог консоли:" !in report.clipboardText)
    }

    @Test
    fun `clipboard text omits webview line when version is null`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val deviceWithoutWebView = testDevice.copy(webViewVersion = null)

        val report = collector.collect(emptyList(), deviceWithoutWebView)

        assertTrue("WebView:" !in report.clipboardText)
    }

    @Test
    fun `issue url contains template and version params`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), testDevice)

        assertTrue(report.issueUrl.startsWith("https://github.com/wrager/sbg-scout/issues/new?"))
        assertTrue(report.issueUrl.contains("template=bug_report.yml"))
        assertTrue(report.issueUrl.contains("apk-version=0.11.0"))
    }

    @Test
    fun `issue url contains android version with webview`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), testDevice)

        // android-version=14 (API 34) (WebView 120.0.6099.144) — URL-encoded
        assertTrue(report.issueUrl.contains("android-version=14%20"))
        assertTrue(report.issueUrl.contains("WebView"))
    }

    @Test
    fun `issue url contains device model`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), testDevice)

        assertTrue(report.issueUrl.contains("device=Google%20Pixel%207"))
    }

    @Test
    fun `issue url contains scripts param when scripts present`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)
        val scripts = listOf(userScript("SVP", "0.6.0"))

        val report = collector.collect(scripts, testDevice)

        assertTrue(report.issueUrl.contains("scripts="))
    }

    @Test
    fun `issue url omits scripts param when no scripts`() {
        every { consoleLogBuffer.format() } returns ""
        val collector = BugReportCollector("0.11.0", consoleLogBuffer)

        val report = collector.collect(emptyList(), testDevice)

        assertTrue("scripts=" !in report.issueUrl)
    }

    @Test
    fun `works without console log buffer`() {
        val collector = BugReportCollector("0.11.0", consoleLogBuffer = null)

        val report = collector.collect(emptyList(), testDevice)

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

        val report = collector.collect(scripts, testDevice)

        assertTrue(report.clipboardText.contains("- Custom Script"))
        assertTrue("- Custom Script v" !in report.clipboardText)
    }

    private fun userScript(name: String, version: String?): UserScript = UserScript(
        identifier = ScriptIdentifier("test/$name"),
        header = ScriptHeader(name = name, version = version),
        sourceUrl = null,
        updateUrl = null,
        content = "",
        enabled = true,
    )
}
