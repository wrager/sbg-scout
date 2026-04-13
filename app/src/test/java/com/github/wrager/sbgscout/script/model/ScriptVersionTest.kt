package com.github.wrager.sbgscout.script.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptVersionTest {

    @Test
    fun `equal versions return zero`() {
        assertEquals(0, ScriptVersion("1.0.0").compareTo(ScriptVersion("1.0.0")))
    }

    @Test
    fun `newer version is greater`() {
        assertTrue(ScriptVersion("1.1.0") > ScriptVersion("1.0.0"))
    }

    @Test
    fun `older version is less`() {
        assertTrue(ScriptVersion("1.0.0") < ScriptVersion("1.1.0"))
    }

    @Test
    fun `major version difference takes precedence`() {
        assertTrue(ScriptVersion("2.0.0") > ScriptVersion("1.99.99"))
    }

    @Test
    fun `version with fewer segments treated as zero-padded`() {
        assertEquals(0, ScriptVersion("1.0").compareTo(ScriptVersion("1.0.0")))
    }

    @Test
    fun `version with more segments is greater when prefix matches`() {
        assertTrue(ScriptVersion("1.0.0.1") > ScriptVersion("1.0.0"))
    }

    @Test
    fun `handles real SVP version comparison`() {
        assertTrue(ScriptVersion("0.5.0") > ScriptVersion("0.4.1"))
    }

    @Test
    fun `handles real CUI version with large numbers`() {
        assertTrue(ScriptVersion("26.1.7") > ScriptVersion("1.14.82"))
    }

    @Test
    fun `handles real EUI version`() {
        assertTrue(ScriptVersion("8.1.0") > ScriptVersion("8.0.9"))
    }

    @Test
    fun `handles non-numeric segments as zero`() {
        assertEquals(0, ScriptVersion("1.0.0-beta").compareTo(ScriptVersion("1.0.0")))
    }

    @Test
    fun `segment with trailing suffix takes leading digits`() {
        // versionNameSuffix = "-debug" / "-instr" из buildType превращает
        // BuildConfig.VERSION_NAME в "0.15.4-debug". Без leading-digits парсинга
        // последний сегмент "4-debug" превращается в 0, и [0, 15, 0] < [0, 15, 4]
        // — AppUpdateChecker ложно находит обновление на ту же версию.
        assertEquals(0, ScriptVersion("0.15.4-debug").compareTo(ScriptVersion("0.15.4")))
        assertEquals(0, ScriptVersion("0.15.4-instr").compareTo(ScriptVersion("0.15.4")))
    }

    @Test
    fun `newer version with suffix is still greater`() {
        assertTrue(ScriptVersion("0.15.5-debug") > ScriptVersion("0.15.4"))
        assertTrue(ScriptVersion("0.15.4") < ScriptVersion("0.15.5-debug"))
    }

    @Test
    fun `older version with suffix is still less`() {
        assertTrue(ScriptVersion("0.15.3-debug") < ScriptVersion("0.15.4"))
    }

    @Test
    fun `single segment versions compare correctly`() {
        assertTrue(ScriptVersion("2") > ScriptVersion("1"))
    }

    @Test
    fun `value accessor returns original string`() {
        // Покрывает сгенерированный getValue() у inline value-class — иначе JaCoCo
        // считает его методом без вызовов.
        assertEquals("1.2.3", ScriptVersion("1.2.3").value)
    }

    @Test
    fun `compareTo works through Comparable bridge in sortedBy`() {
        // Синтетический compareTo(Any) bridge у inline value-class вызывается,
        // когда ScriptVersion используется как Comparable в stdlib алгоритмах.
        val sorted = listOf(
            ScriptVersion("1.10.0"),
            ScriptVersion("1.2.0"),
            ScriptVersion("2.0.0"),
        ).sorted()
        assertEquals(ScriptVersion("1.2.0"), sorted[0])
        assertEquals(ScriptVersion("1.10.0"), sorted[1])
        assertEquals(ScriptVersion("2.0.0"), sorted[2])
    }

    @Test
    fun `empty string version compared to numeric version`() {
        // value.split(".") для "" даёт [""], leadingDigitsAsInt("") → 0.
        // Левая сторона короче → getOrElse default срабатывает для левых segments.
        assertTrue(ScriptVersion("") < ScriptVersion("1.0.0"))
        assertTrue(ScriptVersion("1.0.0") > ScriptVersion(""))
    }

    @Test
    fun `difference at first segment short-circuits rest`() {
        // this > other уже на index=0 — for-loop делает return до getOrElse на
        // последующих индексах. Покрывает ветку, где правый getOrElse вообще не
        // вызывается за отсутствием итераций.
        assertTrue(ScriptVersion("2.0.0") > ScriptVersion("1.99.99"))
        assertTrue(ScriptVersion("1.0.0") < ScriptVersion("2.0.0"))
    }
}
