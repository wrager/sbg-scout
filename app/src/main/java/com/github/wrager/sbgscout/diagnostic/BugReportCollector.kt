package com.github.wrager.sbgscout.diagnostic

import android.os.Build
import android.webkit.WebView
import com.github.wrager.sbgscout.script.model.UserScript

/**
 * Собирает диагностическую информацию для баг-репорта:
 * - устройство, версия Android, версия WebView
 * - версия APK, список включённых скриптов с версиями
 * - лог ошибок JS-консоли
 *
 * Формирует текст для буфера обмена и URL для создания issue на GitHub.
 */
class BugReportCollector(
    private val versionName: String,
    private val consoleLogBuffer: ConsoleLogBuffer?,
) {

    data class DeviceInfo(
        val model: String,
        val androidVersion: String,
        val sdkVersion: Int,
        val webViewVersion: String?,
    ) {
        companion object {
            /** Собирает информацию о текущем устройстве. */
            fun current(): DeviceInfo = DeviceInfo(
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                sdkVersion = Build.VERSION.SDK_INT,
                webViewVersion = getCurrentWebViewVersion(),
            )

            private fun getCurrentWebViewVersion(): String? = try {
                WebView.getCurrentWebViewPackage()?.versionName
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null
            }
        }
    }

    data class DiagnosticReport(
        /** Текст для копирования в буфер обмена (полная диагностика с логом ошибок). */
        val clipboardText: String,
        /** URL для открытия GitHub issue с предзаполненными полями. */
        val issueUrl: String,
    )

    /**
     * Собирает диагностику и формирует отчёт.
     *
     * @param enabledScripts список включённых скриптов
     * @param deviceInfo информация об устройстве (по умолчанию — текущее устройство)
     */
    fun collect(
        enabledScripts: List<UserScript>,
        deviceInfo: DeviceInfo = DeviceInfo.current(),
    ): DiagnosticReport {
        val scriptsText = formatScripts(enabledScripts)
        val consoleLog = consoleLogBuffer?.format().orEmpty()

        val clipboardText = buildClipboardText(deviceInfo, scriptsText, consoleLog)
        val issueUrl = buildIssueUrl(deviceInfo, scriptsText)

        return DiagnosticReport(clipboardText = clipboardText, issueUrl = issueUrl)
    }

    private fun formatScripts(scripts: List<UserScript>): String {
        if (scripts.isEmpty()) return ""
        return scripts.joinToString("\n") { script ->
            val version = script.header.version?.let { " v$it" }.orEmpty()
            "- ${script.header.name}$version"
        }
    }

    private fun buildClipboardText(
        device: DeviceInfo,
        scriptsText: String,
        consoleLog: String,
    ): String = buildString {
        appendLine("SBG Scout v$versionName")
        appendLine("Android ${device.androidVersion} (API ${device.sdkVersion})")
        appendLine("Device: ${device.model}")
        device.webViewVersion?.let { appendLine("WebView: $it") }

        if (scriptsText.isNotEmpty()) {
            appendLine()
            appendLine("Скрипты:")
            appendLine(scriptsText)
        }

        if (consoleLog.isNotEmpty()) {
            appendLine()
            appendLine("Лог консоли:")
            appendLine(consoleLog)
        }
    }.trimEnd()

    private fun buildIssueUrl(device: DeviceInfo, scriptsText: String): String {
        val webViewInfo = device.webViewVersion?.let { " (WebView $it)" }.orEmpty()

        val params = mutableListOf(
            "template" to "bug_report.yml",
            "apk-version" to versionName,
            "android-version" to "${device.androidVersion} (API ${device.sdkVersion})$webViewInfo",
            "device" to device.model,
        )
        if (scriptsText.isNotEmpty()) {
            params += "scripts" to scriptsText
        }

        val query = params.joinToString("&") { (key, value) ->
            "$key=${urlEncode(value)}"
        }
        return "$ISSUES_NEW_URL?$query"
    }

    companion object {
        private const val ISSUES_NEW_URL = "https://github.com/wrager/sbg-scout/issues/new"

        /**
         * URL-кодирование согласно RFC 3986.
         * Пробел → %20 (не +), чтобы корректно работать в query-параметрах GitHub.
         */
        internal fun urlEncode(value: String): String = buildString {
            for (char in value) {
                when {
                    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~" -> append(char)
                    else -> {
                        for (byte in char.toString().toByteArray(Charsets.UTF_8)) {
                            append('%')
                            append(String.format("%02X", byte))
                        }
                    }
                }
            }
        }
    }
}
