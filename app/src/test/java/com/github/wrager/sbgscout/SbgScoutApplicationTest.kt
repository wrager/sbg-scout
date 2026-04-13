package com.github.wrager.sbgscout

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit-тесты для [SbgScoutApplication.applyStoredTheme]/[SbgScoutApplication.applyStoredLanguage].
 *
 * Application.onCreate не вызывается напрямую — вместо этого тестируются
 * `internal`-функции companion-объекта, принимающие уже извлечённые строки.
 * Это устраняет зависимость от `PreferenceManager` и Android runtime.
 */
class SbgScoutApplicationTest {

    @Before
    fun setUp() {
        mockkStatic(AppCompatDelegate::class)
        mockkStatic(LocaleListCompat::class)
        every { AppCompatDelegate.setDefaultNightMode(any()) } just Runs
        every { AppCompatDelegate.setApplicationLocales(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkStatic(AppCompatDelegate::class)
        unmockkStatic(LocaleListCompat::class)
    }

    // --- applyStoredTheme ---

    @Test
    fun `applyStoredTheme does nothing when themeName is null`() {
        SbgScoutApplication.applyStoredTheme(null)

        verify(exactly = 0) { AppCompatDelegate.setDefaultNightMode(any()) }
    }

    @Test
    fun `applyStoredTheme does nothing for unknown theme name`() {
        SbgScoutApplication.applyStoredTheme("NOT_A_THEME")

        verify(exactly = 0) { AppCompatDelegate.setDefaultNightMode(any()) }
    }

    @Test
    fun `applyStoredTheme applies MODE_NIGHT_FOLLOW_SYSTEM for AUTO`() {
        SbgScoutApplication.applyStoredTheme("AUTO")

        verify {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    @Test
    fun `applyStoredTheme applies MODE_NIGHT_YES for DARK`() {
        SbgScoutApplication.applyStoredTheme("DARK")

        verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
    }

    @Test
    fun `applyStoredTheme applies MODE_NIGHT_NO for LIGHT`() {
        SbgScoutApplication.applyStoredTheme("LIGHT")

        verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
    }

    // --- applyStoredLanguage ---

    @Test
    fun `applyStoredLanguage does nothing when language is null`() {
        SbgScoutApplication.applyStoredLanguage(null)

        verify(exactly = 0) { AppCompatDelegate.setApplicationLocales(any()) }
    }

    @Test
    fun `applyStoredLanguage uses empty locale list for sys`() {
        val emptyList = LocaleListCompat.getEmptyLocaleList()
        every { LocaleListCompat.getEmptyLocaleList() } returns emptyList

        SbgScoutApplication.applyStoredLanguage("sys")

        verify { AppCompatDelegate.setApplicationLocales(emptyList) }
    }

    @Test
    fun `applyStoredLanguage uses forLanguageTags for explicit tag`() {
        val locales = LocaleListCompat.forLanguageTags("ru")
        every { LocaleListCompat.forLanguageTags("ru") } returns locales

        SbgScoutApplication.applyStoredLanguage("ru")

        verify { AppCompatDelegate.setApplicationLocales(locales) }
    }
}
