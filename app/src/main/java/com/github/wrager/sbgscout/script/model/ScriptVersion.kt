package com.github.wrager.sbgscout.script.model

@JvmInline
value class ScriptVersion(val value: String) : Comparable<ScriptVersion> {

    override fun compareTo(other: ScriptVersion): Int {
        val thisSegments = value.split(".").map(::leadingDigitsAsInt)
        val otherSegments = other.value.split(".").map(::leadingDigitsAsInt)
        val maxLength = maxOf(thisSegments.size, otherSegments.size)

        // Прямые indexing с явным check `index < size` вместо `getOrElse` с lambda —
        // JaCoCo считает lambda `{ 0 }` отдельной branch, которую сложно триггерить
        // из тестов при Kotlin inline-подстановке для `map` через `leadingDigitsAsInt`.
        for (index in 0 until maxLength) {
            val thisSegment = if (index < thisSegments.size) thisSegments[index] else 0
            val otherSegment = if (index < otherSegments.size) otherSegments[index] else 0
            if (thisSegment != otherSegment) return thisSegment.compareTo(otherSegment)
        }

        return 0
    }

    private companion object {
        /**
         * Возвращает leading digits сегмента как Int. Нужно для случаев, когда
         * последний сегмент содержит суффикс — `"4-debug"`, `"4-beta"`,
         * `"4+build123"`. Без этого versionNameSuffix = "-debug" превращает
         * `"0.15.4-debug"` в `[0, 15, 0]` и AppUpdateChecker ложно находит
         * «обновление» с тегом `v0.15.4`.
         */
        fun leadingDigitsAsInt(segment: String): Int {
            val prefix = segment.takeWhile { it.isDigit() }
            return prefix.toIntOrNull() ?: 0
        }
    }
}
