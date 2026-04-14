package com.github.wrager.sbgscout.e2e.flows.smoke

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.hamcrest.core.CombinableMatcher.both
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет мост [com.github.wrager.sbgscout.bridge.ShareBridge]
 * (регистрируется как `__sbg_share`).
 *
 * `__sbg_share.open(url)` запускает `Intent.ACTION_VIEW` с данным URL,
 * что на устройстве пользователя открывает внешний браузер. В тесте мы
 * перехватываем все исходящие intent'ы через Espresso-Intents, чтобы
 * эмулятор не пытался реально запустить сторонний браузер.
 */
@RunWith(AndroidJUnit4::class)
class ShareBridgeE2ETest : E2ETestBase() {

    @Test
    fun open_startsActionViewIntentWithGivenUrl() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        // Intents.init() ПОСЛЕ launch, чтобы сам intent запуска Activity
        // не попал в перехватчик. В try/finally, чтобы Intents.release()
        // точно вызвался даже при падении ассерта.
        Intents.init()
        try {
            // Перехватываем все исходящие intent'ы, чтобы реальный браузер
            // не стартовал — эмулятор может его не иметь и тест упадёт с ANR.
            Intents.intending(anyIntent())
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            val targetUrl = "https://example.com/share-test"
            game.evaluateJs("__sbg_share.open('$targetUrl')")

            Intents.intended(
                both(hasAction(Intent.ACTION_VIEW))
                    .and(hasData(Uri.parse(targetUrl))),
            )
        } finally {
            Intents.release()
        }
    }
}
