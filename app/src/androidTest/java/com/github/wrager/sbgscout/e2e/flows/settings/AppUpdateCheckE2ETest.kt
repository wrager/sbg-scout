package com.github.wrager.sbgscout.e2e.flows.settings

import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Полный цикл автопроверки обновлений приложения в GameActivity:
 *
 * 1. prefs auto_check_updates=true, last_update_check=0 → scheduleAutoUpdateCheck
 *    в GameActivity.onCreate запускает фоновую проверку через GithubReleaseProvider.
 * 2. HttpRewriter перенаправляет запрос на `/gh-api/repos/wrager/sbg-scout/releases`.
 * 3. Fake-сервер возвращает релиз с версией новее BuildConfig.VERSION_NAME.
 * 4. AppUpdateChecker возвращает UpdateAvailable → GameActivity.showAppUpdateDialog
 *    показывает AlertDialog с кнопкой "Download".
 * 5. Проверяем, что диалог с заголовком "App update available" виден на экране.
 *
 * Этот тест сознательно не наследует disableAutoUpdateCheck из E2ETestBase —
 * переопределяет prefs после base setUp, чтобы автопроверка сработала.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateCheckE2ETest : E2ETestBase() {

    @Test
    fun scheduleAutoUpdateCheck_withNewerReleaseOnFakeGithub_showsUpdateDialog() {
        // Фиктивно гигантская версия — заведомо больше BuildConfig.VERSION_NAME.
        val newVersion = "999.0.0"
        val releaseJson = GithubReleaseFixtures.appUpdateReleasesJson(
            version = newVersion,
            body = "Huge release with many features",
        )
        server.stubGithubReleasesList("wrager", "sbg-scout", releaseJson)
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        // Переопределяем disableAutoUpdateCheck из E2ETestBase.
        prefs.edit()
            .putBoolean("auto_check_updates", true)
            .putLong("last_update_check", 0L)
            .commit()

        val scenario = launchGameActivity()
        GameScreen(scenario, idling).waitForLoaded()

        val title = targetContext.getString(R.string.app_update_available)
        waitForViewWithText(title)
        onView(withText(title)).check(matches(isDisplayed()))

        val downloadLabel = targetContext.getString(R.string.app_update_download)
        onView(withText(downloadLabel)).check(matches(isDisplayed()))
    }

    private fun waitForViewWithText(text: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        throw AssertionError("View с текстом '$text' не появился за ${TIMEOUT_MS}ms")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 200L
    }
}
