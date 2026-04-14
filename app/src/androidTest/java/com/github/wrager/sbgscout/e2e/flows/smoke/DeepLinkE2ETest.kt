package com.github.wrager.sbgscout.e2e.flows.smoke

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deep-link сценарий: внешнее приложение кликает на `sbg-game.ru/...`,
 * Android доставляет `ACTION_VIEW`-intent в `GameActivity`, и WebView
 * грузит именно тот URL (с query/fragment), а не дефолтный `GameUrls.appUrl`.
 *
 * В instr-сборке host игры — `127.0.0.1:<port>` (fake-сервер), не `sbg-game.ru`;
 * intent-filter в манифесте на прод-хост в instr-тестах не участвует, т.к.
 * `ActivityScenario.launch(intent)` ставит component явно. Тест проверяет
 * код-путь `GameActivity.extractDeepLinkUrl` + `GameUrls.isGameUrl`.
 *
 * Покрыта только ветка `onCreate` (холодный запуск Activity по deep-link).
 * Ветка `onNewIntent` (deep-link в уже живой инстанс) не покрывается e2e из-за
 * ограничений `ActivityScenario`: после `callActivityOnNewIntent` lifecycle-
 * трекинг scenario не даёт закрыть Activity в `tearDown` (застревает в RESUMED
 * до таймаута 45с). Сама реализация `onNewIntent` — 5 строк, делегирующих
 * `extractDeepLinkUrl` + `webView.loadUrl`, те же функции покрыты через
 * `onCreate`-путь.
 *
 * В instr `launchMode` переопределён на `singleTop` через `tools:replace`
 * (см. `app/src/instr/AndroidManifest.xml`) — в prod используется `singleTask`,
 * но он несовместим с `ActivityScenario.close()`.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkE2ETest : E2ETestBase() {

    @Test
    fun launch_withViewIntent_loadsDeepLinkUrlInWebView() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val deepLinkUri = Uri.parse("${server.baseUrl}/app?pt=deeplink-fixture")
        val intent = Intent(Intent.ACTION_VIEW, deepLinkUri)
        val scenario = launchGameActivityWithIntent(intent)
        val game = GameScreen(scenario, idling).waitForLoaded()

        // WebView подгрузил URL из intent, а не дефолтный GameUrls.appUrl —
        // query-string виден в location.search.
        assertEquals("\"?pt=deeplink-fixture\"", game.evaluateJs("location.search"))
    }
}
