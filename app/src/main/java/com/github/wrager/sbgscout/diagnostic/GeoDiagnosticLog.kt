package com.github.wrager.sbgscout.diagnostic

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Потокобезопасный кольцевой буфер для записей о работе геолокации в WebView.
 *
 * Заполняется только в `diag`/`debug` сборках через `BuildConfig.GEOLOCATION_DIAGNOSTICS`.
 * В release/instr `add()` всё равно вызывается из guard-обёртки в [com.github.wrager.sbgscout.GameActivity],
 * но в release этих guard-вызовов нет, поэтому буфер пуст.
 *
 * Содержимое в человекочитаемом виде включается в diagnostic clipboard через
 * [BugReportCollector] при наличии непустого `format()`.
 */
class GeoDiagnosticLog {

    data class Entry(val timestamp: Instant, val message: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(message: String) {
        entries.addLast(Entry(Instant.now(), message))
        if (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun format(): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { entry ->
            "[${FORMATTER.format(entry.timestamp)}] ${entry.message}"
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val MAX_ENTRIES = 100
        private val FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
