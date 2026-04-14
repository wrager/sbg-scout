package com.github.wrager.sbgscout.e2e.flows.settings

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.CoreMatchers.not
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.BuildConfig
import com.github.wrager.sbgscout.R
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.GithubReleaseFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.junit.Test

/**
 * Ручная проверка обновлений приложения из settings overlay.
 *
 * Клик preference `check_app_update` → `SettingsFragment.checkAppUpdate` →
 * `GameActivity.showAppUpdateCheckDialog` — единый 3-фазный диалог:
 * 1) Checking (indeterminate прогресс + title `app_update_checking`);
 * 2) Result — переключается на `app_update_available` / `app_up_to_date` /
 *    `app_update_check_failed` по результату `AppUpdateChecker.check()`;
 * 3) Download — только при UpdateAvailable после клика "Download".
 *
 * Тесты покрывают все три ветки фазы 2 через стабление
 * `/gh-api/repos/wrager/sbg-scout/releases` разными JSON'ами. Клик "Download"
 * в UpdateAvailable-тесте сознательно не выполняется — он запустил бы реальный
 * `AppUpdateInstaller.downloadAndInstall` и PackageInstaller на устройстве.
 *
 * Отличие от AppUpdateCheckE2ETest: тот покрывает авто-проверку на старте
 * (scheduleAutoUpdateCheck → showAppUpdateDialog), здесь — ручной триггер из
 * overlay через checkAppUpdate → showAppUpdateCheckDialog. Это разные кодовые
 * пути с разными диалогами.
 */
class ManualAppUpdateCheckE2ETest : E2ETestBase() {

    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun checkAppUpdate_withNewerReleaseOnFakeGithub_showsUpdateAvailableWithDownloadButton() {
        server.stubGithubReleasesList(
            "wrager",
            "sbg-scout",
            GithubReleaseFixtures.appUpdateReleasesJson(
                version = VERSION_FAR_AHEAD,
                body = "Lots of new features",
            ),
        )
        openSettingsAndClickCheckAppUpdate()

        val availableTitle = targetContext.getString(R.string.app_update_available)
        waitForDialogTitle(availableTitle)

        val downloadLabel = targetContext.getString(R.string.app_update_download)
        onView(withText(downloadLabel)).inRoot(isDialog()).check(matches(isDisplayed()))
        // Клик Download не делаем — он запустил бы AppUpdateInstaller.downloadAndInstall
        // и реальный PackageInstaller на устройстве.
    }

    @Test
    fun checkAppUpdate_withCurrentVersionOnFakeGithub_showsUpToDate() {
        // Возвращаем релиз с тем же tag_name, что и BuildConfig.VERSION_NAME —
        // AppUpdateChecker сравнивает по ScriptVersion, `latestVersion <= current`
        // даёт UpToDate.
        server.stubGithubReleasesList(
            "wrager",
            "sbg-scout",
            GithubReleaseFixtures.appUpdateReleasesJson(
                version = BuildConfig.VERSION_NAME,
                body = "Current",
            ),
        )
        openSettingsAndClickCheckAppUpdate()

        val upToDateTitle = targetContext.getString(R.string.app_up_to_date)
        waitForDialogTitle(upToDateTitle)

        // В ветке UpToDate кнопка Download скрыта (downloadButton.visibility = GONE).
        val downloadLabel = targetContext.getString(R.string.app_update_download)
        // Кнопка Download физически создана в диалоге через setPositiveButton, но
        // в ветке UpToDate/CheckFailed её visibility остаётся GONE — ViewMatchers
        // различает «не существует» и «существует, но не displayed».
        onView(withText(downloadLabel)).inRoot(isDialog()).check(matches(not(isDisplayed())))
    }

    @Test
    fun checkAppUpdate_withMissingReleasesStubOnFakeGithub_showsCheckFailed() {
        // Сознательно НЕ стабим /gh-api/repos/wrager/sbg-scout/releases —
        // FakeGameDispatcher вернёт 404, GithubReleaseProvider бросит исключение,
        // AppUpdateChecker.check поймает и вернёт CheckFailed.
        openSettingsAndClickCheckAppUpdate()

        val failedTitle = targetContext.getString(R.string.app_update_check_failed)
        waitForDialogTitle(failedTitle)

        val downloadLabel = targetContext.getString(R.string.app_update_download)
        // Кнопка Download физически создана в диалоге через setPositiveButton, но
        // в ветке UpToDate/CheckFailed её visibility остаётся GONE — ViewMatchers
        // различает «не существует» и «существует, но не displayed».
        onView(withText(downloadLabel)).inRoot(isDialog()).check(matches(not(isDisplayed())))
    }

    private fun openSettingsAndClickCheckAppUpdate() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val overlay = GameScreen(scenario, idling).waitForLoaded().openSettings()
        overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_CHECK_APP_UPDATE)
    }

    /**
     * Ждёт появления в корне диалога View с заданным текстом. Используется вместо
     * `onView(withText(...)).inRoot(isDialog()).check(matches(isDisplayed()))` с
     * немедленной проверкой — диалог открывается до того, как coroutine из
     * `showAppUpdateCheckDialog` успевает получить ответ от fake-сервера, поэтому
     * title меняется асинхронно через `dialog.setTitle(...)`.
     */
    private fun waitForDialogTitle(text: String) {
        val deadline = SystemClock.uptimeMillis() + TITLE_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()))
                return
            } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        throw AssertionError("Диалог с title='$text' не появился за ${TITLE_WAIT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val VERSION_FAR_AHEAD = "999.0.0"
        const val TITLE_WAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
