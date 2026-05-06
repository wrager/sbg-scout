package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что WebView отдаёт `navigator.geolocation.getCurrentPosition`
 * результат в JS-callback, когда у приложения есть Android-permission на
 * геолокацию.
 *
 * Регрессия, на которую тест защищает: цепочка
 * `WebSettings.setGeolocationEnabled(true)` ->
 * `WebChromeClient.onGeolocationPermissionsShowPrompt` ->
 * `GeolocationPermissions.Callback.invoke(origin, true, false)` -> fix от
 * LocationManager. Если хоть одно звено сломано (выпил `setGeolocationEnabled`,
 * не вызвали `invoke`, кеш per-origin permissions с deny-state),
 * `__sbg_geo_result` останется `null` или придёт `{ok: false}`.
 *
 * Permission на FINE/COARSE выдаётся [E2ETestBase] через `GrantPermissionRule`.
 * Координаты подаются эмулятором по умолчанию (mock location provider в
 * Android emulator активен сразу, отдельный `adb emu geo fix` не требуется).
 */
@RunWith(AndroidJUnit4::class)
class GeolocationE2ETest : E2ETestBase() {

    @Test
    fun navigatorGeolocation_returnsPosition_whenAndroidPermissionGranted() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-with-geolocation.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        val raw = waitForGeoResult(game)

        assertNotEquals(
            "navigator.geolocation не отдал результата за ${RESULT_TIMEOUT_MS}ms — " +
                "callback от WebView, скорее всего, не дошёл до JS",
            "null",
            raw,
        )

        // evaluateJavascript возвращает JSON-encoded результат: исходная JS-строка
        // приходит как `"..."` с escape'нутым содержимым. Сначала снимаем внешний
        // JSON-уровень, потом парсим внутренний JSON, который положила фикстура.
        val payload = JSONTokener(raw).nextValue() as String
        val obj = JSONObject(payload)
        assertTrue(
            "Геолокация вернула ошибку вместо позиции: $payload",
            obj.optBoolean("ok", false),
        )
    }

    private fun waitForGeoResult(game: GameScreen): String {
        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
        var last = "null"
        while (System.currentTimeMillis() < deadline) {
            last = game.evaluateJs("window.__sbg_geo_result")
            if (last != "null") return last
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return last
    }

    private companion object {
        // Фикстура запрашивает getCurrentPosition с timeout: 8000.
        // Здесь даём ещё ~2с на сетевые/IPC расходы, итого 10с.
        const val RESULT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
