package com.github.wrager.sbgscout.script.model

@JvmInline
value class ScriptVersion(
    val value: String,
) : Comparable<ScriptVersion> {
    override fun compareTo(other: ScriptVersion): Int {
        val thisSegments = numericPart.split(".").map { it.toIntOrNull() ?: 0 }
        val otherSegments = other.numericPart.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(thisSegments.size, otherSegments.size)

        // Отсутствующие числовые компоненты считаются нулями.
        for (index in 0 until maxLength) {
            val thisSegment = if (index < thisSegments.size) thisSegments[index] else 0
            val otherSegment = if (index < otherSegments.size) otherSegments[index] else 0
            if (thisSegment != otherSegment) return thisSegment.compareTo(otherSegment)
        }

        return 0
    }

    /** Стабильный релиз обновляет скрипт с тем же номером, если у установленной версии есть суффикс. */
    fun isUpdateFor(currentVersion: ScriptVersion): Boolean {
        val numberComparison = compareTo(currentVersion)
        return numberComparison > 0 ||
            (numberComparison == 0 && numericPart == value && currentVersion.numericPart != currentVersion.value)
    }

    private val numericPart: String
        get() = value.takeWhile { it.isDigit() || it == '.' }
}
