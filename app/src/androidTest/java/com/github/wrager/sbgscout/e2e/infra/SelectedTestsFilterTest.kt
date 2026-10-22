package com.github.wrager.sbgscout.e2e.infra

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedTestsFilterTest {
    private val first = Description.createTestDescription("com.example.FirstTest", "opensScreen")
    private val second = Description.createTestDescription("com.example.FirstTest", "savesPreference")
    private val other = Description.createTestDescription("com.example.OtherTest", "opensScreen")

    @Test
    fun selectedTests_runOnlyListedMethods() {
        val filter = filterFor("com.example.FirstTest#opensScreen:com.example.OtherTest#opensScreen")

        assertTrue(filter.shouldRun(first))
        assertFalse(filter.shouldRun(second))
        assertTrue(filter.shouldRun(other))
    }

    @Test
    fun suites_runWhenAnyChildIsSelected() {
        val suite = Description.createSuiteDescription("com.example.FirstTest")
        suite.addChild(first)
        suite.addChild(second)

        assertTrue(filterFor("com.example.FirstTest#savesPreference").shouldRun(suite))
        assertFalse(filterFor("com.example.OtherTest#opensScreen").shouldRun(suite))
    }

    @Test
    fun missingArgument_selectsNothing() {
        assertFalse(SelectedTestsFilter(Bundle()).shouldRun(first))
    }

    private fun filterFor(tests: String) =
        SelectedTestsFilter(Bundle().apply { putString(SelectedTestsFilter.ARGUMENT, tests) })
}
