package com.github.wrager.sbgscout.e2e.flows.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import com.github.wrager.sbgscout.e2e.screens.SettingsOverlayScreen
import org.hamcrest.core.CombinableMatcher.both
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Клик «Открывать ссылки sbg-game.ru в приложении» в settings overlay должен
 * запускать системный Intent, ведущий на экран назначения default handler для
 * нашего приложения:
 * - Android 12+ (API 31): `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` — попадает
 *   сразу на «Supported web addresses» нашего приложения.
 * - Android < 12: `ACTION_APPLICATION_DETAILS_SETTINGS` (fallback — нет
 *   прямого экрана).
 *
 * Программно назначить приложение обработчиком нельзя — это ограничение
 * безопасности Android. Тест проверяет именно запуск правильного системного
 * intent'а; что пользователь делает на открывшемся экране — вне scope.
 */
@RunWith(AndroidJUnit4::class)
class OpenLinksInAppE2ETest : E2ETestBase() {

    @Test
    fun openLinksInAppPreference_launchesSystemDefaultAppSettingsIntent() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()
        val overlay = game.openSettings()

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedUri = Uri.parse("package:${targetContext.packageName}")
        val expectedAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }

        Intents.init()
        try {
            // Заглушаем все исходящие intent'ы, чтобы эмулятор не запустил
            // реальный системный экран (и тест не завис на его диалоге).
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            overlay.clickPreferenceByKey(SettingsOverlayScreen.KEY_OPEN_LINKS_IN_APP)

            Intents.intended(
                both(hasAction(expectedAction)).and(hasData(expectedUri)),
            )
        } finally {
            Intents.release()
        }
    }
}
